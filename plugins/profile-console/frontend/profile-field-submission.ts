import type { ProfilePlacement } from "@/platform/profile-fields/types";

type ProfileFieldSettingsRecord = Record<string, unknown> & {
  key?: string;
  placements?: ProfilePlacement[];
  publicRegistrationAllowed?: boolean;
};

/** Preserve schema metadata not represented by a registered Form.Item. */
export function mergeProfileFieldSettings(
  snapshot: Record<string, unknown>,
  submitted: Record<string, unknown>,
): Record<string, unknown> {
  const originalFields = Array.isArray(snapshot.fields) ? snapshot.fields as ProfileFieldSettingsRecord[] : [];
  const submittedFields = Array.isArray(submitted.fields) ? submitted.fields as ProfileFieldSettingsRecord[] : null;
  if (!submittedFields) {
    const merged = { ...snapshot, ...submitted };
    if (Object.hasOwn(submitted, "fields")) merged.fields = snapshot.fields;
    return merged;
  }

  const originalByKey = new Map(originalFields.filter(field => typeof field.key === "string").map(field => [field.key!, field]));
  const fields = submittedFields.map(field => {
    const original = typeof field.key === "string" ? originalByKey.get(field.key) : undefined;
    const merged = { ...original, ...field };
    if (!Array.isArray(field.placements) && original && Object.hasOwn(original, "placements")) {
      merged.placements = original.placements;
    }
    if (typeof field.publicRegistrationAllowed !== "boolean" && original && Object.hasOwn(original, "publicRegistrationAllowed")) {
      merged.publicRegistrationAllowed = original.publicRegistrationAllowed;
    }
    return merged;
  });
  return { ...snapshot, ...submitted, fields };
}
