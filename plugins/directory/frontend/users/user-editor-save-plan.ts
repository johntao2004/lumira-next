import { preserveProfileFieldValues } from "../../../../apps/web/src/platform/profile-fields/values.ts";

type ProfileBaseline = { displayName: string; enabled: boolean };
type ProfileValuesSnapshot = {
  schemaVersion: number;
  fields: readonly { key: string }[];
  values: Readonly<Record<string, string>>;
};

export type UserEditorSavePlan = {
  hasChanges: boolean;
  profileChanged: boolean;
  profileValuesChanged: boolean;
  payload: {
    version: number;
    profile?: { displayName: string; enabled: boolean };
    profileSchemaVersion?: number;
    profileValues?: Record<string, string>;
    roleIds?: string[];
    organizationIds?: string[];
  };
};

/** Build one editor request from the full form store and the snapshots used to open it. */
export function buildUserEditorSavePlan(input: {
  version: number;
  baseline: ProfileBaseline;
  formValues: Readonly<Record<string, unknown>>;
  profileValues?: ProfileValuesSnapshot;
  canSaveProfile: boolean;
  roleIds?: string[];
  organizationIds?: string[];
}): UserEditorSavePlan {
  const displayName = typeof input.formValues.displayName === "string"
    ? input.formValues.displayName
    : input.baseline.displayName;
  const enabled = typeof input.formValues.enabled === "boolean"
    ? input.formValues.enabled
    : input.baseline.enabled;
  const profileChanged = input.canSaveProfile
    && (displayName !== input.baseline.displayName || enabled !== input.baseline.enabled);
  const nextProfileValues = input.canSaveProfile && input.profileValues
    ? preserveProfileFieldValues(input.formValues, input.profileValues.fields, input.profileValues.values)
    : undefined;
  const profileValuesChanged = !!(nextProfileValues && input.profileValues
    && input.profileValues.fields.some(field => nextProfileValues[field.key] !== (input.profileValues!.values[field.key] ?? "")));
  const hasChanges = profileChanged || profileValuesChanged
    || input.roleIds !== undefined || input.organizationIds !== undefined;

  return {
    hasChanges,
    profileChanged,
    profileValuesChanged,
    payload: {
      version: input.version,
      ...(profileChanged ? { profile: { displayName: displayName.trim(), enabled } } : {}),
      ...(profileValuesChanged && nextProfileValues && input.profileValues
        ? { profileSchemaVersion: input.profileValues.schemaVersion, profileValues: nextProfileValues }
        : {}),
      ...(input.roleIds !== undefined ? { roleIds: input.roleIds } : {}),
      ...(input.organizationIds !== undefined ? { organizationIds: input.organizationIds } : {}),
    },
  };
}
