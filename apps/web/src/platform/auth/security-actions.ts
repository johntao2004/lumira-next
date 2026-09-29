import type {MessageKey} from "../i18n/messages";
/** Shared security event vocabulary for personal history and administrator audit. */
export const accountSecurityActions:Record<string,MessageKey>={
 "account.loginSucceeded":"loginSucceeded",
 "account.loginFailed":"loginFailed",
 "account.loginRateLimited":"loginRateLimited",
 "account.loginRejected":"loginRejected",
 "account.passwordChanged":"passwordChanged",
 "account.passwordRecoveryRequested":"requestRecoveryCode",
 "account.passwordRecovered":"passwordRecovered",
 "account.passwordRecoveryFailed":"invalidRecoveryToken",
 "account.emailVerificationRequested":"requestEmailCode",
 "account.emailVerified":"emailVerified",
 "account.emailVerificationFailed":"invalidVerificationToken",
 "session.revoke":"logout",
};
