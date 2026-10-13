export interface CurrentUser {
  userId: string;
  enabledModules: string[];
  username: string;
  displayName: string;
  authorities: string[];
  globalAuthorities: string[];
  /** Opaque revision for effective grants and data scopes; independent of preview revision. */
  authorizationRevision?: string | null;
  authenticationEvidence?: AuthenticationEvidence;
  effectiveAuthentication?: EffectiveAuthentication;
  authorizationContext?: AuthorizationContext;
}

export type PrimaryMethod = "PASSWORD" | "EMAIL_OTP" | "SMS_OTP" | "PASSKEY" | "WECHAT";

export type LoginState =
  | "PRIMARY_REQUIRED"
  | "CAPTCHA_REQUIRED"
  | "MFA_REQUIRED"
  | "ENROLLMENT_REQUIRED"
  | "RECOVERY_REQUIRED"
  | "PASSWORD_CHANGE_REQUIRED"
  | "COMPLETE"
  | "EXPIRED"
  | "LOCKED"
  | "CANCELLED"
  | "NONE";

export interface AuthenticationEvidence {
  primaryMethod: PrimaryMethod | null;
  verifiedFactors: string[];
  authenticatedAt: string | null;
  authenticationRevision: string | null;
}

export interface AuthenticationRequirementSource {
  kind: "GLOBAL" | "ROLE" | "SELF_ENABLED" | "RECOVERY_PENDING";
  roleId?: string;
}

export interface EffectiveAuthentication {
  allowedMethods: PrimaryMethod[];
  usableMethods: PrimaryMethod[];
  mfaRequired: boolean;
  requirementSources: AuthenticationRequirementSource[];
}

export interface AuthorizationContext {
  revision: string | null;
  rolePreviewSupported: boolean;
  preview: RolePreviewState | null;
}

export interface RolePreviewState {
  state: "ACTIVE" | "BLOCKED";
  roleId: string;
  roleName: string;
  roleVersion: number;
  startedAt: string;
  expiresAt: string;
  limitedByActor: boolean;
  blockedReason: string | null;
}

export interface RolePreviewCandidate {
  id: string;
  code: string;
  name: string;
  version: number;
  dataScope: string;
  assigned: boolean;
  limitedByActor: boolean;
}

export interface RolePreviewPage {
  items: RolePreviewCandidate[];
  total: number;
  page: number;
  pageSize: number;
}

export type WechatPurpose = "LOGIN" | "BIND" | "REGISTRATION";

export interface WechatAttempt {
  attemptId: string;
  purpose: WechatPurpose;
  authorizationUrl: string;
  expiresAt: string;
}

export interface WechatResult {
  status: "NONE" | "PENDING" | "READY" | "UNBOUND" | "FAILED" | "EXPIRED" | "CANCELLED";
  attemptId: string | null;
  purpose: WechatPurpose | null;
  expiresAt: string | null;
  login: LoginResponse | null;
  wechatRegistrationProofId: string | null;
  identity: { nickname: string | null } | null;
  failureCode: string | null;
}

export interface WechatAccount {
  version: number;
  bound: boolean;
  usable: boolean;
  identity: { nickname: string | null } | null;
}

export interface WechatSettings {
  version: number;
  enabled: boolean;
  appId: string;
  publicOrigin: string;
  timeoutSeconds: number;
  appSecretConfigured: boolean;
  ready: boolean;
}

export interface WechatSettingsUpdate {
  version: number;
  enabled: boolean;
  appId: string;
  publicOrigin: string;
  timeoutSeconds: number;
  appSecret: { action: "KEEP" | "REPLACE" | "CLEAR"; value?: string };
}

export interface LoginMethodAvailability {
  methods: PrimaryMethod[];
  registration: {
    supported: boolean;
    available: boolean;
    channels: string[];
    path: string | null;
  };
  capabilities: {
    loginCode: boolean;
    wechat: boolean;
    captcha: boolean;
    rolePreview: boolean;
  };
  captcha: {
    required: boolean;
    type: string | null;
    actions: string[];
    alwaysActions: string[];
  };
}

export interface AuthenticationPolicy {
  enabledMethods: string[];
  usableMethods: string[];
  mfaRequired: boolean;
  requirementSources: AuthenticationRequirementSource[];
  authenticator: {
    available: boolean;
    configured: boolean | null;
    recoveryCodesRemaining: number | null;
  };
  authenticationRevision: string;
}

export interface AuthenticationSettings {
  version: number;
  revision: string;
  enabledMethods: PrimaryMethod[];
  mfaRequired: boolean;
  /** Provider readiness, independent of the global enabled-method policy. */
  availableMethods: PrimaryMethod[];
  affectedAccountCount: number;
}

export type CaptchaAction =
  | "PASSWORD_LOGIN" | "PASSKEY_LOGIN" | "WECHAT_LOGIN"
  | "LOGIN_CODE_SEND" | "LOGIN_CODE_VERIFY"
  | "REGISTRATION_SEND" | "REGISTRATION_VERIFY" | "REGISTRATION_COMPLETE"
  | "PASSWORD_RECOVERY_REQUEST" | "PASSWORD_RECOVERY_VERIFY" | "PASSWORD_RECOVERY_CONFIRM"
  | "POST_PRIMARY_AUTH";

export type CaptchaMode = "OFF" | "RISK" | "ALWAYS";

export interface CaptchaSettingsData {
  enabled: boolean;
  provider: "LOCAL";
  type: "IMAGE_TEXT" | "SLIDER";
  actions: Record<CaptchaAction, CaptchaMode>;
}

export interface CaptchaSettings {
  version: number;
  revision: string;
  data: CaptchaSettingsData;
  secretEncryptionConfigured: boolean;
  readiness: { configured: boolean; accessibleAlternativeAvailable: boolean };
}

export interface CaptchaSettingsUpdate {
  version: number;
  data: CaptchaSettingsData;
}

export interface LocalCaptchaChallenge {
  challengeId: string;
  action: CaptchaAction;
  attemptId: string | null;
  expiresAt: string;
  provider: "LOCAL";
  type: "IMAGE_TEXT" | "SLIDER" | "AUDIO";
  accessibleAlternativeAvailable: boolean;
  imageDataUrl?: string;
  backgroundDataUrl?: string;
  pieceDataUrl?: string;
  audioDataUrl?: string;
  width?: number;
  height?: number;
  pieceWidth?: number;
  pieceHeight?: number;
  pieceY?: number;
}

export type RoleLoginMode = "INHERIT" | "RESTRICT";
export type RoleMfaMode = "INHERIT" | "REQUIRED";
export type RoleCaptchaMode = "INHERIT" | "ALWAYS";

export interface RoleAuthenticationPolicy {
  roleId: string;
  roleVersion: number;
  version: number;
  revision: string;
  loginMode: RoleLoginMode;
  methods: PrimaryMethod[];
  mfaMode: RoleMfaMode;
  captchaMode?: RoleCaptchaMode;
  affectedAccountCount: number;
}

export type ReauthenticationAction =
  | "MFA_ENROLL"
  | "MFA_REPLACE"
  | "MFA_RENAME"
  | "MFA_RECOVERY_REGENERATE"
  | "MFA_DISABLE"
  | "MFA_ADMIN_RESET"
  | "WECHAT_BIND"
  | "WECHAT_UNBIND";

export interface ReauthenticationStart {
  challengeId: string;
  expiresAt: string;
  methods: ReauthenticationMethod[];
  factorRequired: boolean;
}

export type ReauthenticationMethod = "PASSWORD" | "EMAIL_OTP" | "SMS_OTP" | "PASSKEY";

export interface ReauthenticationCodeChallenge {
  codeChallengeId: string;
  expiresAt: string;
  resendAt: string;
}

export interface ReauthenticationTicket {
  ticketId: string;
  expiresAt: string;
  action: ReauthenticationAction;
}

export interface ReauthenticationCompletion {
  challengeId: string;
  method: ReauthenticationMethod;
  password?: string;
  factorCode?: string;
  codeChallengeId?: string;
  code?: string;
}

export interface PasskeyOptions {
  challengeId: string;
  publicKey: Record<string, unknown>;
}

export interface PasskeyAuthenticationAvailability {
  enabled: boolean;
}

export interface PasskeyCredential {
  id: string;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export type LoginResponse = {
  authenticated: boolean;
  secondFactorRequired: boolean;
  challengeId: string | null;
  expiresAt: string | null;
  state?: LoginState;
  policyRevision?: string | null;
  factors?: string[];
  nextPath?: string | null;
};

export interface EnrollmentConfirmationResponse {
  login: LoginResponse;
  recoveryCodes: string[];
}
