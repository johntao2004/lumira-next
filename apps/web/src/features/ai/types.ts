export type ProviderKind = "OLLAMA" | "LM_STUDIO" | "OPENAI" | "DEEPSEEK" | "QWEN" | "ZHIPU" | "SILICONFLOW" | "OPENROUTER" | "MOONSHOT" | "CUSTOM_OPENAI" | "TYPESAFE_JEV";
export type CapabilityCode = "CHAT" | "TOOL_CALLING" | "VISION" | "EMBEDDING" | "CHOICE" | "SCORE" | "NOUL";
export type Provider = {
  id: string;
  name: string;
  providerKind: ProviderKind;
  baseUrl: string;
  modelName: string;
  capabilityCodes: CapabilityCode[];
  enabled: boolean;
  version: number;
  credentialConfigured: boolean;
};
export type ChatAttachment = { id: string; name: string; sizeBytes: number; image: boolean; truncated: boolean };
export type ChatMessage = { role: "user" | "assistant"; content: string; attachmentIds?: string[] };
export type ActionProposal = {
  id: string;
  kind: "CREATE_USER" | "UPDATE_USER" | "DELETE_USER";
  target: { id: string; username: string; displayName: string; enabled: boolean; version: number } | null;
  username: string | null;
  displayName: string | null;
  enabled: boolean | null;
  expiresAt: string;
};
export type ExecutionStep = {
  code: "MODEL_RESPONSE" | "USER_SEARCH" | "ACTION_PROPOSAL" | "ACTION_CONFIRMATION" | "TOOL_VALIDATION";
  status: "success" | "error" | "pending" | "abort";
  moduleCode?: string;
  operationCode?: string;
};
export type ChatResponse = { message: string; users: { id: string; username: string; displayName: string; enabled: boolean; version: number }[] | null; proposal: ActionProposal | null; steps?: ExecutionStep[] };
export type ModelOption = {
  id: string;
  name: string;
  inputModalities: string[];
  outputModalities: string[];
  capabilityCodes?: CapabilityCode[];
};
export type ConnectionResult = { models: ModelOption[]; configuredModelAvailable: boolean };

export const providerKindLabel: Record<ProviderKind, string> = {
  OLLAMA: "aiKindOllama",
  LM_STUDIO: "aiKindLmStudio",
  OPENAI: "aiKindOpenAi",
  DEEPSEEK: "aiKindDeepSeek",
  QWEN: "aiKindQwen",
  ZHIPU: "aiKindZhipu",
  SILICONFLOW: "aiKindSiliconFlow",
  OPENROUTER: "aiKindOpenRouter",
  MOONSHOT: "aiKindMoonshot",
  CUSTOM_OPENAI: "aiKindCustomOpenAi",
  TYPESAFE_JEV: "aiKindJev",
};

export const providerDefaultBaseUrl: Record<ProviderKind, string> = {
  OLLAMA: "http://127.0.0.1:11434/v1",
  LM_STUDIO: "http://127.0.0.1:1234/v1",
  OPENAI: "https://api.openai.com/v1",
  DEEPSEEK: "https://api.deepseek.com",
  QWEN: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  ZHIPU: "https://open.bigmodel.cn/api/paas/v4",
  SILICONFLOW: "https://api.siliconflow.cn/v1",
  OPENROUTER: "https://openrouter.ai/api/v1",
  MOONSHOT: "https://api.moonshot.cn/v1",
  CUSTOM_OPENAI: "",
  TYPESAFE_JEV: "https://api.typesafe.ai",
};

export function providerRequiresCredential(kind: ProviderKind): boolean {
  return kind !== "OLLAMA" && kind !== "LM_STUDIO";
}
