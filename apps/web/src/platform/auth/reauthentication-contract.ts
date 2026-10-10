import type {
  ReauthenticationCompletion,
  ReauthenticationMethod,
  ReauthenticationStart,
} from "./api-types";

export interface ReauthenticationFormValues {
  reauthenticationMethod?: ReauthenticationMethod;
  reauthenticationCodeChallengeId?: string;
  reauthenticationCode?: string;
  currentPassword?: string;
  factorCode?: string;
}

export function reauthenticationCompletionBody(
  challenge: ReauthenticationStart,
  values: ReauthenticationFormValues,
): ReauthenticationCompletion | null {
  const method = values.reauthenticationMethod ?? challenge.methods[0];
  if (!method || !challenge.methods.includes(method) || method === "PASSKEY") return null;
  const factorCode = challenge.factorRequired ? values.factorCode?.trim() : undefined;
  if (challenge.factorRequired && !factorCode) return null;
  if (method === "PASSWORD") {
    const password = values.currentPassword;
    if (!password) return null;
    return { challengeId: challenge.challengeId, method, password, ...(factorCode ? { factorCode } : {}) };
  }
  const codeChallengeId = values.reauthenticationCodeChallengeId;
  const code = values.reauthenticationCode?.trim();
  if (!codeChallengeId || !code) return null;
  return { challengeId: challenge.challengeId, method, codeChallengeId, code, ...(factorCode ? { factorCode } : {}) };
}
