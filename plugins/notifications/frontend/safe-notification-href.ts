/** Return only unambiguous root-relative notification destinations. */
export function safeNotificationHref(value: unknown): string | null {
  if (
    typeof value !== "string" ||
    value.length > 512 ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    /[\u0000-\u001f\u007f-\u009f]/.test(value)
  ) return null;

  const query = value.indexOf("?");
  const fragment = value.indexOf("#");
  const pathEnd = Math.min(
    query < 0 ? value.length : query,
    fragment < 0 ? value.length : fragment,
  );
  // WHATWG special URLs treat path backslashes like slashes. In query/hash they are inert.
  if (value.slice(0, pathEnd).includes("\\")) return null;
  return value;
}
