export interface CurrentUser {
  enabledModules: string[];
  username: string;
  displayName: string;
  authorities: string[];
  globalAuthorities: string[];
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
