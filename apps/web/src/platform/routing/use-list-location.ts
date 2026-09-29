"use client";
import { history, useLocation } from "@umijs/max";
import { readListLocation } from "./list-location";
import { createListRequest, type ListRequest } from "../api/pagination";

const defaultListRequest = createListRequest();

/** URL-backed state preserves history without remounting the workspace shell. */
export function useLocationParams() {
  const location = useLocation();
  const params = new URLSearchParams(location.search);
  const update = (changes: Record<string, string | null>) => {
    const url = new URL(window.location.href);
    for (const [key, value] of Object.entries(changes)) {
      if (value === null) url.searchParams.delete(key); else url.searchParams.set(key, value);
    }
    const next = url.pathname + url.search + url.hash;
    if (next !== window.location.pathname + window.location.search + window.location.hash) history.push(next);
  };
  return [params, update] as const;
}
export function useListLocation(prefix = "") {
  const [params, update] = useLocationParams();
  const request = readListLocation(new URLSearchParams(params.toString()), prefix);
  const setRequest = (next: ListRequest) => update({
    [prefix + "page"]: next.page === defaultListRequest.page ? null : String(next.page),
    [prefix + "pageSize"]: next.pageSize === defaultListRequest.pageSize ? null : String(next.pageSize),
    [prefix + "search"]: next.search || null,
  });
  return [request, setRequest] as const;
}
