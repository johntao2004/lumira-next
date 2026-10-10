import type { ReauthenticationFormValues } from "@/platform/auth/reauthentication-contract";

export type MfaStatus = {
  enabled: boolean;
  label: string | null;
  createdAt: string | null;
  recoveryCodesRemaining: number;
};

export type MfaSetup = { challengeId: string; secret: string; uri: string; expiresAt: string };
export type MfaRecovery = { recoveryCodes: string[] };

export type MfaAccount = {
  id: string;
  username: string;
  displayName: string;
  label: string | null;
  createdAt: string | null;
  recoveryCodesRemaining: number;
};

export type MfaActionValues = ReauthenticationFormValues & {
  code?: string;
  label?: string;
  reason?: string;
};

export const mfaErrorKeys = [
  "wrongCurrentPassword",
  "secondFactorInvalid",
  "secondFactorExpired",
  "secondFactorUnavailable",
  "mfaSetupExpired",
  "mfaAlreadyEnabled",
  "mfaNotEnabled",
  "mfaLabelRequired",
  "mfaReplacementExpired",
  "mfaRecoveryDeliveryUnavailable",
  "mfaRecoveryNotPending",
  "mfaRecoveryLimited",
  "mfaRequiredByPolicy",
  "mfaResetReasonRequired",
  "mfaUseSelfService",
  "loginRateLimited",
  "reauthenticationInvalid",
  "reauthenticationExpired",
  "reauthenticationLocked",
  "reauthenticationMethodUnavailable",
  "reauthenticationCodeLimited",
  "reauthenticationPasskeyUseFirst",
  "passkeyUnsupported",
  "passkeyCancelled",
  "passkeyTimeout",
  "passkeyOperationFailed",
  "forbidden",
] as const;
