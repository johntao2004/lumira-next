"use client";

import { useEffect } from "react";
import { history, useLocation } from "@umijs/max";
import { readTabAnchor, tabAnchorName, updateFragment } from "./tab-anchor";

/** Read the current URL at write time so concurrent tab groups cannot overwrite one another. */
export function navigateFragment(changes: Record<string, string | null>, mode: "push" | "replace" = "push") {
  const { pathname, search, hash } = window.location;
  const nextHash = updateFragment(hash, changes);
  if (nextHash !== hash) history[mode](`${pathname}${search}${nextHash}`, history.location.state);
}

export function useFragmentParams() {
  const { hash } = useLocation();
  return [new URLSearchParams(hash.replace(/^#/, "")), navigateFragment] as const;
}

/** The URL is the source of truth, including refresh, direct links and browser history. */
export function useTabAnchor<const Key extends string>(scope: string, keys: readonly Key[], fallback: Key, enabled = true) {
  const location = useLocation();
  const activeKey = readTabAnchor(location.hash, scope, keys, fallback) as Key;
  const name = tabAnchorName(scope);

  useEffect(() => {
    if (!enabled || !keys.length) return;
    // Ignore an effect from a page which has already been left.
    if (window.location.pathname !== location.pathname) return;
    const current = readTabAnchor(window.location.hash, scope, keys, fallback);
    navigateFragment({ [name]: current }, "replace");
  }, [enabled, location.pathname, location.hash, name, fallback, keys.join("\0")]);

  const setActiveKey = (key: string) => {
    if (enabled && keys.includes(key as Key)) navigateFragment({ [name]: key });
  };
  return [activeKey, setActiveKey] as const;
}
