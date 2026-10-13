const LOGIN_STATES = new Set([
  "PRIMARY_REQUIRED",
  "CAPTCHA_REQUIRED",
  "MFA_REQUIRED",
  "ENROLLMENT_REQUIRED",
  "RECOVERY_REQUIRED",
  "PASSWORD_CHANGE_REQUIRED",
  "COMPLETE",
  "EXPIRED",
  "LOCKED",
  "CANCELLED",
  "NONE",
]);

export function isLoginResponse(value) {
  if (!value || typeof value !== "object") return false;
  return typeof value.authenticated === "boolean"
    && typeof value.secondFactorRequired === "boolean"
    && (value.challengeId === null || typeof value.challengeId === "string")
    && (value.state === undefined || LOGIN_STATES.has(value.state));
}

/** Resolve server-authored login state before considering authenticated access. */
export function loginResponseDisposition(result) {
  if (result.state === "PASSWORD_CHANGE_REQUIRED") return "password-change";
  if (result.state === "ENROLLMENT_REQUIRED") return "enrollment";
  if (result.state === "MFA_REQUIRED" || result.secondFactorRequired) return "mfa";
  if (result.state === "CAPTCHA_REQUIRED" && result.challengeId) return "captcha";
  if (result.state === "RECOVERY_REQUIRED") return "recovery";
  if ((result.state === undefined || result.state === "COMPLETE") && result.authenticated === true) return "complete";
  return "unavailable";
}
