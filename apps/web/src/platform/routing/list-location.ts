import { createListRequest, TABLE_PAGE_SIZE_OPTIONS } from "../api/pagination.ts";

const defaultListRequest = createListRequest();

export function readListLocation(params: URLSearchParams, prefix = "") {
  const integer = (key: string, fallback: number, max: number) => {
    const raw = params.get(prefix + key);
    if (!raw || !/^\d+$/.test(raw)) return fallback;
    const value = Number(raw);
    return Number.isSafeInteger(value) && value >= 1 && value <= max ? value : fallback;
  };
  return { page: integer("page", defaultListRequest.page, 2147483647), pageSize: integer("pageSize", defaultListRequest.pageSize, Math.max(...TABLE_PAGE_SIZE_OPTIONS)), search: (params.get(prefix + "search") ?? defaultListRequest.search).slice(0, 100) };
}
export function locationId(value: string | null): string | null {
  return value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) ? value : null;
}
