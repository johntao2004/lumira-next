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

/** Preserve valid selections in their original order; when none remain, use the first discovered model. */
export function selectDiscoveredModels(models: readonly ModelOption[], current: readonly string[] = []): string[] {
  const available = new Set(models.map((model) => model.id));
  const retained = [...new Set(current)].filter((model) => available.has(model));
  return retained.length ? retained : models[0] ? [models[0].id] : [];
}

/** Editors without discovery access can change the default while retaining configured fallbacks. */
export function modelNamesAfterManualEdit(savedModels: readonly string[], requestedDefault?: string): string[] {
  const model = requestedDefault?.trim();
  if (!model) return [];
  const uniqueSaved = [...new Set(savedModels)];
  if (uniqueSaved.includes(model)) return [model, ...uniqueSaved.filter((saved) => saved !== model)];
  return [model, ...uniqueSaved.slice(1)];
}

/** Submit the editor's visible selection control for the user's model-management access level. */
export function modelNamesForSave(canDiscover: boolean, savedModels: readonly string[], discoveredSelection?: readonly string[], manualDefault?: string): string[] {
  return canDiscover ? [...(discoveredSelection ?? [])] : modelNamesAfterManualEdit(savedModels, manualDefault);
}

/** Reopening an unchanged provider preserves its saved model; changed settings require a fresh result. */
export function modelSelectionIsValid(savedModel: string | undefined, value: string | undefined,
  discovery: ModelDiscoverySnapshot): boolean {
  if (!value) return false;
  if (discovery.models.some((model) => model.id === value)) return true;
  return savedModel === value && !discovery.invalidated && (discovery.status === "idle" || discovery.status === "error");
}

/** Validate every selected identifier against discovery or an unchanged saved configuration. */
export function modelSelectionsAreValid(savedModels: readonly string[], value: readonly string[] | undefined,
  discovery: ModelDiscoverySnapshot): boolean {
  if (!value?.length) return false;
  if (value.every((model) => discovery.models.some((option) => option.id === model))) return true;
  const saved = new Set(savedModels);
  return value.every((model) => saved.has(model)) && !discovery.invalidated &&
    (discovery.status === "idle" || discovery.status === "error");
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
