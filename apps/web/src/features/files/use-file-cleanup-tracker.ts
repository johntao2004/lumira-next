"use client";

import { useCallback, useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/platform/api/client";

type CleanupState = { state: string };

/**
 * Poll only file IDs whose purge has been accepted, so list and usage queries
 * refresh when metadata cleanup releases the file's quota. PostgreSQL blob
 * chunks may remain retired for 24 hours after this point.
 */
export function useFileCleanupTracker() {
  const client = useQueryClient();
  const [pendingIds, setPendingIds] = useState<string[]>([]);

  const trackFileCleanup = useCallback((id: string) => {
    setPendingIds(current => current.includes(id) ? current : [...current, id]);
  }, []);

  useEffect(() => {
    if (pendingIds.length === 0) return;

    let stopped = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    let delayMs = 1_000;
    const abort = new AbortController();

    const poll = async () => {
      const results = await Promise.all(pendingIds.map(async id => {
        try {
          const entry = await api<CleanupState>(`/account/files/${id}`, { signal: abort.signal });
          return { id, complete: entry.state !== "DELETING" };
        } catch (error) {
          if (error instanceof ApiError && error.status === 404) return { id, complete: true };
          return { id, complete: false };
        }
      }));

      if (stopped) return;
      const completed = new Set(results.filter(result => result.complete).map(result => result.id));
      if (completed.size > 0) {
        setPendingIds(current => current.filter(id => !completed.has(id)));
        await client.invalidateQueries({ queryKey: ["files"] });
      }

      if (!stopped && pendingIds.some(id => !completed.has(id))) {
        timeout = setTimeout(poll, delayMs);
        delayMs = Math.min(delayMs * 2, 15_000);
      }
    };

    timeout = setTimeout(poll, delayMs);
    return () => {
      stopped = true;
      abort.abort();
      if (timeout) clearTimeout(timeout);
    };
  }, [client, pendingIds]);

  return trackFileCleanup;
}
