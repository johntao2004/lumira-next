import { createListRequest, TABLE_PAGE_SIZE_OPTIONS, type ListRequest } from "../api/pagination.ts";

const defaultListRequest = createListRequest();

export function readListLocation(params: URLSearchParams, prefix = "") {
  const integer = (key: string, fallback: number, max: number) => {
    const raw = params.get(prefix + key);
    if (!raw || !/^\d+$/.test(raw)) return fallback;
    const value = Number(raw);
    return Number.isSafeInteger(value) && value >= 1 && value <= max ? value : fallback;
  };
  const filters: Record<string, string> = Object.create(null);
  const filterModes: NonNullable<ListRequest["filterModes"]> = Object.create(null);
  const fields = new Set<string>();
  const safeField = (key: string) => /^[A-Za-z][A-Za-z0-9_.-]{0,100}$/.test(key);
  const columnField = (key: string) => key.replace(/\.(?:from|to)$/, "");
  for (const [key, value] of params) {
    if (key.startsWith(prefix + "filter.")) {
      const field = key.slice((prefix + "filter.").length);
      const column = columnField(field);
      if (!safeField(field) || !safeField(column) || value.length > 512) continue;
      if (!fields.has(column) && fields.size >= 40) continue;
      fields.add(column);
      filters[field] = value;
    } else if (key.startsWith(prefix + "filterMode.")) {
      const field = key.slice((prefix + "filterMode.").length);
      if (!safeField(field) || !["contains", "exact", "range"].includes(value)) continue;
      if (!fields.has(field) && fields.size >= 40) continue;
      fields.add(field);
      filterModes[field] = value as "contains" | "exact" | "range";
    }
  }
  return {
    ...(Object.keys(filters).length ? { filters: { ...filters } } : {}),
    ...(Object.keys(filterModes).length ? { filterModes: { ...filterModes } } : {}),
    page: integer("page", defaultListRequest.page, 2147483647),
    pageSize: integer("pageSize", defaultListRequest.pageSize, Math.max(...TABLE_PAGE_SIZE_OPTIONS)),
    search: (params.get(prefix + "search") ?? defaultListRequest.search).slice(0, 100),
  };
}
export function locationId(value: string | null): string | null {
  return value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) ? value : null;
}

/** Replace the current list state while retaining sibling lists and unrelated query fields. */
export function listLocationChanges(params: URLSearchParams, next: ListRequest, prefix = ""): Record<string, string | null> {
  const changes: Record<string, string | null> = Object.create(null);
  changes[prefix + "page"] = next.page === defaultListRequest.page ? null : String(next.page);
  changes[prefix + "pageSize"] = next.pageSize === defaultListRequest.pageSize ? null : String(next.pageSize);
  changes[prefix + "search"] = next.search.slice(0, 100) || null;
  for (const key of params.keys()) {
    if (key.startsWith(prefix + "filter.") || key.startsWith(prefix + "filterMode.")) changes[key] = null;
  }

  const fields = new Set<string>();
  const safeField = (key: string) => /^[A-Za-z][A-Za-z0-9_.-]{0,100}$/.test(key);
  const columnField = (key: string) => key.replace(/\.(?:from|to)$/, "");
  for (const [key, value] of Object.entries(next.filters ?? {})) {
    const column = columnField(key);
    if (!safeField(key) || !safeField(column) || value.length > 512) continue;
    if (!fields.has(column) && fields.size >= 40) continue;
    fields.add(column);
    changes[prefix + "filter." + key] = value || null;
  }
  for (const [key, value] of Object.entries(next.filterModes ?? {})) {
    if (!safeField(key) || !["contains", "exact", "range"].includes(value)) continue;
    if (!fields.has(key) && fields.size >= 40) continue;
    fields.add(key);
    changes[prefix + "filterMode." + key] = value;
  }
  return changes;
}
