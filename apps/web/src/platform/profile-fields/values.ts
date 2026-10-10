import type { ProfileFieldDefinition } from "./types";

/** Submit the current form edit while retaining fields not mounted by the active tab. */
export function preserveProfileFieldValues(
  formValues: Readonly<Record<string, unknown>>,
  fields: readonly Pick<ProfileFieldDefinition, "key">[],
  savedValues: Readonly<Record<string, string>>,
): Record<string, string> {
  return Object.fromEntries(fields.map(({ key }) => [
    key,
    String(Object.hasOwn(formValues, key) ? formValues[key] ?? "" : savedValues[key] ?? ""),
  ]));
}
