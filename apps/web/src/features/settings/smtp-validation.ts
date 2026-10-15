/** The SMTP contract permits single-label hosts and ASCII dot-atom mailboxes. */
export function smtpHostValid(value: string): boolean {
  return value.length > 0 && value.length <= 253
    && value.split(".").every(label => /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/.test(label));
}

export function smtpMailboxValid(value: string): boolean {
  const at = value.indexOf("@");
  if (at < 1 || at > 64 || at !== value.lastIndexOf("@") || value.length > 254) return false;
  const local = value.slice(0, at);
  return !local.startsWith(".") && !local.endsWith(".") && !local.includes("..")
    && /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local)
    && smtpHostValid(value.slice(at + 1));
}

export function smtpTextValid(value: unknown, max: number): value is string {
  return typeof value === "string" && value.length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
}
