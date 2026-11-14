export function preserveProfileFieldValues(
  formValues: Readonly<Record<string, unknown>>,
  fields: readonly { readonly key: string }[],
  savedValues: Readonly<Record<string, string>>,
): Record<string, string> {
  return Object.fromEntries(fields.map(({ key }) => [
    key,
    String(Object.hasOwn(formValues, key) ? formValues[key] ?? "" : savedValues[key] ?? ""),
  ]));
}
