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
const PASSWORD_CHANGE_REASONS = new Set(["BOOTSTRAP", "TEMPORARY_RESET"]);
const PASSWORD_CHANGE_CONTINUATION_STATES = new Set([
  "CAPTCHA_REQUIRED",
  "MFA_REQUIRED",
  "ENROLLMENT_REQUIRED",
  "RECOVERY_REQUIRED",
]);

export function isLoginResponse(value) {
  if (!value || typeof value !== "object") return false;
  if (value.state === "PASSWORD_CHANGE_REQUIRED" && value.passwordChangeReason !== undefined
    && !PASSWORD_CHANGE_REASONS.has(value.passwordChangeReason)) return false;
  return typeof value.authenticated === "boolean"
    && typeof value.secondFactorRequired === "boolean"
    && (value.challengeId === null || typeof value.challengeId === "string")
    && (value.state === undefined || LOGIN_STATES.has(value.state));
}

/** Older servers only emitted PASSWORD_CHANGE_REQUIRED for administrative temporary resets. */
export function passwordChangeReason(result) {
  return result.passwordChangeReason === "BOOTSTRAP" ? "BOOTSTRAP" : "TEMPORARY_RESET";
}

/** Accept only a complete login or an explicit, internally consistent next step after password setup. */
export function isPasswordChangeContinuationResponse(value) {
  if (!isLoginResponse(value)) return false;
  if (value.state !== "COMPLETE" && !PASSWORD_CHANGE_CONTINUATION_STATES.has(value.state)) return false;
  if (value.expiresAt !== undefined && value.expiresAt !== null && typeof value.expiresAt !== "string") return false;
  if (value.policyRevision !== undefined && value.policyRevision !== null && typeof value.policyRevision !== "string") return false;
  if (value.factors !== undefined && (!Array.isArray(value.factors) || !value.factors.every(factor => typeof factor === "string"))) return false;
  if (value.nextPath !== undefined && value.nextPath !== null && typeof value.nextPath !== "string") return false;
  if (value.passwordChangeReason !== undefined && value.passwordChangeReason !== null
    && !PASSWORD_CHANGE_REASONS.has(value.passwordChangeReason)) return false;

  if (value.state === "COMPLETE") {
    return value.authenticated === true
      && value.secondFactorRequired === false
      && value.challengeId === null;
  }

  return value.authenticated === false
    && value.secondFactorRequired === true
    && typeof value.challengeId === "string"
    && value.challengeId.trim().length > 0;
}

/** Resolve server-authored login state before considering authenticated access. */
export function loginResponseDisposition(result) {
  if (result.state === "PASSWORD_CHANGE_REQUIRED") return "password-change";
  if (result.state === "ENROLLMENT_REQUIRED") return "enrollment";
  if (result.state === "CAPTCHA_REQUIRED" && result.challengeId) return "captcha";
  if (result.state === "RECOVERY_REQUIRED") return "recovery";
  if (result.state === "MFA_REQUIRED" || (!result.state && result.secondFactorRequired)) return "mfa";
  if ((result.state === undefined || result.state === "COMPLETE") && result.authenticated === true) return "complete";
  return "unavailable";
}
