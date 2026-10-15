import { isPasswordChangeContinuationResponse } from "./login-response-contract.mjs";

/** Ignore stale password-change replies before interpreting their server payload. */
export function resolvePasswordChangeContinuation({
  response,
  challengeId,
  activeChallengeId,
  submittedSessionRevision,
  currentSessionRevision,
}) {
  if (!challengeId
    || activeChallengeId !== challengeId
    || submittedSessionRevision !== currentSessionRevision) {
    return { status: "stale" };
  }
  if (!isPasswordChangeContinuationResponse(response)) return { status: "invalid" };
  return { status: "continue", response };
}
