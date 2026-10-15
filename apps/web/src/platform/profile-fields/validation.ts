import type { ProfileFieldDefinition } from "./types";

// Match String.strip(), which the server applies before validating profile values.
const boundaryWhitespace = /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Mirrors ProfileValues without converting decimal strings through JavaScript numbers. */
export function profileFieldValueValid(field: ProfileFieldDefinition, input: unknown): boolean {
  if (input == null || input === "") return !field.required;
  if (typeof input !== "string" && !(field.type === "NUMBER" && typeof input === "number")) return false;
  const value = String(input).replace(boundaryWhitespace, "");
  if (value.includes("\0")) return false;
  if (!value) return !field.required;
  if (value.length > (field.type === "TEXTAREA" ? 2000 : 200)) return false;

  if (field.type === "NUMBER") {
    if (!/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(value)) return false;
    const scale = value.split(".")[1]?.length ?? 0;
    const precision = value.replace(/[-.]/g, "").replace(/^0+/, "").length || 1;
    return precision <= 30 && scale <= 10;
  }
  if (field.type === "DATE") {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) return false;
    const [, year, month, day] = match.map(Number);
    const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
    const days = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
  }
  if (field.type === "SELECT") {
    return field.dictionaryCode
      ? uuidPattern.test(value)
      : (field.options ?? []).some(option => (typeof option === "string" ? option : option.value) === value);
  }
  return true;
}
