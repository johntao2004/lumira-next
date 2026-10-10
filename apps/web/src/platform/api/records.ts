import { mutate } from "./client";
import type { ListRequest } from "./pagination";

export function saveRecord<T = unknown>(endpoint: string, id: string | null | undefined, values: object) {
  return mutate<T>(id ? `${endpoint}/${encodeURIComponent(id)}` : endpoint, id ? "PUT" : "POST", values);
}

/** Keep a list on a valid page after removing its final visible row. */
export function pageAfterRemoval(request: ListRequest, visibleCount: number | undefined): ListRequest {
  return visibleCount === 1 && request.page > 1 ? { ...request, page: request.page - 1 } : request;
}
