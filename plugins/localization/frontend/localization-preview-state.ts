export type LocalizationAiPreviewScope = Readonly<{
  generation: number;
  sourceLocale: string;
  targetLocale: string;
  providerId: string;
  modelName: string;
  providerVersion: number | null;
  sourceBundleVersion: number | null;
  sourceSnapshotToken: string;
  overwriteManual: boolean;
  scope: string;
}>;

export function sameLocalizationAiScope(
  left: LocalizationAiPreviewScope | null | undefined,
  right: LocalizationAiPreviewScope | null | undefined,
) {
  return Boolean(left && right && left.generation === right.generation &&
    left.sourceLocale === right.sourceLocale && left.targetLocale === right.targetLocale &&
    left.providerId === right.providerId && left.modelName === right.modelName &&
    left.providerVersion === right.providerVersion &&
    left.sourceBundleVersion === right.sourceBundleVersion &&
    left.sourceSnapshotToken === right.sourceSnapshotToken &&
    left.overwriteManual === right.overwriteManual && left.scope === right.scope);
}

/** Late responses from superseded generations are ignored; failed retries keep valid text. */
export function mergeLocalizationAiResults<T extends { translation?: string; entry: { version: number } }>(
  current: Record<string, T>,
  patch: Record<string, T>,
  expectedGeneration: number,
  currentGeneration: number,
) {
  if (expectedGeneration !== currentGeneration) return current;
  const next = { ...current };
  for (const [key, incoming] of Object.entries(patch)) {
    const previous = current[key];
    next[key] = previous?.translation && !incoming.translation &&
      previous.entry.version === incoming.entry.version
      ? previous
      : incoming;
  }
  return next;
}

export type LocalizationBundlePreviewScope<TBundle, TResult> = Readonly<{
  generation: number;
  bundle: TBundle;
  overwrite: boolean;
  result: TResult;
}>;

export function isCurrentLocalizationBundlePreview<TBundle, TResult>(
  preview: LocalizationBundlePreviewScope<TBundle, TResult> | null | undefined,
  current: { generation: number; bundle: TBundle | null; overwrite: boolean },
) {
  return Boolean(preview && preview.generation === current.generation &&
    preview.bundle === current.bundle && preview.overwrite === current.overwrite);
}
