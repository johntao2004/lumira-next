import type { ListRequest, PageResult } from "@/platform/api/pagination";

export type TranslationScope = "page" | "selected" | "all-missing";
type TranslationEntry = {
  id: string; messageKey: string; enabled: boolean; overridden: boolean; valueSource: string;
};

export function needsTranslation(row: TranslationEntry) {
  return !row.overridden && (row.valueSource === "FALLBACK" || row.valueSource === "MISSING");
}

/** The whole-catalogue scope deliberately ignores table search and pagination. */
export async function loadTranslationCandidates<T extends TranslationEntry>(
  options: { scope: TranslationScope; request: ListRequest; selectedKeys: string[]; onlyMissing: boolean; signal: AbortSignal },
  loadPage: (request: ListRequest) => Promise<PageResult<T>>,
): Promise<T[]> {
  const { scope, selectedKeys, onlyMissing, signal } = options;
  let request = scope === "all-missing"
    ? { page: 1, pageSize: 100, search: "", filters: {}, filterModes: {} }
    : options.request;
  const candidates = new Map<string, T>();
  for (;;) {
    signal.throwIfAborted();
    const result = await loadPage(request);
    for (const row of result.items) {
      if (!row.enabled || (scope === "selected" && !selectedKeys.includes(row.id))) continue;
      if ((scope === "all-missing" || onlyMissing) && !needsTranslation(row)) continue;
      candidates.set(row.messageKey, row);
    }
    if (scope !== "all-missing" || !result.items.length || result.page * result.pageSize >= result.total) break;
    request = { ...request, page: result.page + 1 };
  }
  return [...candidates.values()];
}
