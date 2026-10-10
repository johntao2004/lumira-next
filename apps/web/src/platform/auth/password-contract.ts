export const MAX_PASSWORD_BYTES = 256;
export const DEFAULT_PASSWORD_MIN_LENGTH = 12;
export type PasswordRules = {
  minLength: number;
  maxBytes: number;
  requireUppercase: boolean;
  requireLowercase: boolean;
  requireSpecialCharacter: boolean;
  allowConsecutiveCharacters: boolean;
};

export const DEFAULT_PASSWORD_RULES: PasswordRules = {
  minLength: DEFAULT_PASSWORD_MIN_LENGTH,
  maxBytes: MAX_PASSWORD_BYTES,
  requireUppercase: false,
  requireLowercase: false,
  requireSpecialCharacter: false,
  allowConsecutiveCharacters: true,
};

const encoder = new TextEncoder();

export function passwordWithinMaximum(value: unknown): value is string {
  return typeof value === "string" && encoder.encode(value).length <= MAX_PASSWORD_BYTES;
}

export function defaultPasswordLengthValid(value: unknown): value is string {
  return typeof value === "string"
    && Array.from(value).length >= DEFAULT_PASSWORD_MIN_LENGTH
    && passwordWithinMaximum(value);
}

/** Validate against a server-provided public rule set without changing legacy defaults. */
export function passwordValidForRules(value: unknown, rules: PasswordRules): value is string {
  if (typeof value !== "string" || new TextEncoder().encode(value).length > rules.maxBytes) return false;
  const points = Array.from(value, character => character.codePointAt(0) ?? 0);
  if (points.length < rules.minLength) return false;
  let upper = false;
  let lower = false;
  let special = false;
  let repeatedOrSequential = false;
  const folded = points.map(point => Array.from(String.fromCodePoint(point).toLowerCase(), value => value.codePointAt(0) ?? point)[0] ?? point);
  for (let index = 0; index < points.length; index += 1) {
    const character = String.fromCodePoint(points[index]);
    upper ||= /\p{Lu}/u.test(character);
    lower ||= /\p{Ll}/u.test(character);
    special ||= !/[\p{L}\p{Nd}]/u.test(character);
    if (index > 0 && points[index] === points[index - 1]) repeatedOrSequential = true;
    if (index > 1) {
      const [first, second, third] = points.slice(index - 2, index + 1).map(point => String.fromCodePoint(point));
      const sameClass = ([first, second, third].every(item => /\p{L}/u.test(item)))
        || ([first, second, third].every(item => /\p{Nd}/u.test(item)));
      const step = folded[index - 1] - folded[index - 2];
      if (sameClass && (step === 1 || step === -1) && folded[index] - folded[index - 1] === step) repeatedOrSequential = true;
    }
  }
  return (!rules.requireUppercase || upper)
    && (!rules.requireLowercase || lower)
    && (!rules.requireSpecialCharacter || special)
    && (rules.allowConsecutiveCharacters || !repeatedOrSequential);
}
