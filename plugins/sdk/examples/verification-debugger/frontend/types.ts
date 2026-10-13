export const DEBUG_RECIPIENT = "+15555550123";

export type DebuggerStatus = {
  enabled: boolean;
  receiverReady: boolean;
  allowedRecipient: string | null;
};

export type SessionConfiguration = Record<string, unknown>;

export type DebuggerSessionResponse = {
  id: string;
  expiresAt: string;
  endpoint: string;
  bearerToken: string;
  recipient: string;
  configuration: SessionConfiguration;
  providers?: Partial<Record<MockProvider, Partial<MockProviderSettings>>>;
};

export type OwnedDebuggerSession = DebuggerSessionResponse & {
  ownerUserId: string;
};

export type DebuggerMessage = {
  id: string;
  receivedAt: string;
  recipient: string;
  message: string;
  code: string;
  provider: string;
};

export type DebuggerMessagesResponse = {
  items: DebuggerMessage[];
  cursor: string | null;
};

export type MockProvider = "aliyun" | "tencent";

export type MockProviderSettings = {
  endpoint: string;
  accessKeyId?: string;
  accessKeySecret?: string;
  secretId?: string;
  secretKey?: string;
};

export type InvokeResult = {
  status: number;
  body: unknown;
};
