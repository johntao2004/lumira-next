export const MAX_PASSWORD_BYTES = 256;
export const DEFAULT_PASSWORD_MIN_LENGTH = 12;

const encoder = new TextEncoder();

export function passwordWithinMaximum(value: unknown): value is string {
  return typeof value === "string" && encoder.encode(value).length <= MAX_PASSWORD_BYTES;
}

export function defaultPasswordLengthValid(value: unknown): value is string {
  return typeof value === "string"
    && Array.from(value).length >= DEFAULT_PASSWORD_MIN_LENGTH
    && passwordWithinMaximum(value);
}
