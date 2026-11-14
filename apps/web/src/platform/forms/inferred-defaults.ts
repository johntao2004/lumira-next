/** 只补充可解释的默认值；显式编辑（包括清空）始终优先。
 * Fill explainable defaults only; explicit edits, including clearing, always win. */
export function fileDisplayName(filename: string, maxLength: number): string {
  const base = filename.split(/[\\/]/).pop() ?? "";
  return base.replace(/\.[^.]*$/, "").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, maxLength);
}

export function inferredFileName(current: unknown, previous: string | null, edited: boolean, filename: string, maxLength: number): string | null {
  if (edited || (current !== undefined && current !== null && current !== "" && current !== previous)) return null;
  return fileDisplayName(filename, maxLength) || null;
}

export function smtpPortForSecurity(previous: unknown, next: string, port: number | null | undefined, edited: boolean): number | null | undefined {
  const standard = (security: unknown) => security === "TLS" ? 465 : security === "STARTTLS" ? 587 : undefined;
  if (edited) return port;
  if (port === null || port === undefined || port === standard(previous)) return standard(next) ?? port;
  return port;
}
