import type { ModelOption } from "./types";

export type DiscoveryErrorKey =
  | "aiProviderAuthenticationFailed"
  | "aiModelDiscoveryEndpointUnavailable"
  | "aiProviderRateLimited"
  | "aiProviderRequestTimedOut"
  | "aiProviderCredentialRequired"
  | "aiModelDiscoveryFailed";

export type ModelDiscoverySnapshot = {
  models: readonly ModelOption[];
  status: "idle" | "success" | "empty" | "error";
  invalidated: boolean;
};

/** Keep only identifiers returned by the provider; never synthesize picker entries. */
export function selectDiscoveredModel(models: readonly ModelOption[], current?: string): string | undefined {
  if (current && models.some((model) => model.id === current)) return current;
  return models.length === 1 ? models[0].id : undefined;
}

/** Reopening an unchanged provider preserves its saved model; changed settings require a fresh result. */
export function modelSelectionIsValid(savedModel: string | undefined, value: string | undefined,
  discovery: ModelDiscoverySnapshot): boolean {
  if (!value) return false;
  if (discovery.models.some((model) => model.id === value)) return true;
  return savedModel === value && !discovery.invalidated && (discovery.status === "idle" || discovery.status === "error");
}

/** Modalities are displayed only when the provider included them in its model response. */
export function modelOptionLabel(model: ModelOption): string {
  const identity = model.name && model.name !== model.id ? `${model.name} (${model.id})` : model.id;
  return model.inputModalities.length ? `${identity} · ${model.inputModalities.join(", ")}` : identity;
}

/** Convert stable, sanitized API error codes to localized UI keys. */
export function discoveryErrorKey(error: unknown): DiscoveryErrorKey {
  const candidate = error && typeof error === "object" ? error as { message?: unknown; status?: unknown } : {};
  const code = typeof candidate.message === "string" ? candidate.message : "";
  if (["aiProviderAuthenticationFailed", "aiModelDiscoveryEndpointUnavailable", "aiProviderRateLimited",
    "aiProviderRequestTimedOut", "aiProviderCredentialRequired"].includes(code)) {
    return code as DiscoveryErrorKey;
  }
  if (candidate.status === 504) return "aiProviderRequestTimedOut";
  return "aiModelDiscoveryFailed";
}
