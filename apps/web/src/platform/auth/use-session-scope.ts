"use client";

import { useCallback, useEffect, useRef } from "react";
import { sessionSignal } from "./session-lifecycle";

/** Bind an async continuation to its starting account epoch and component lifetime. */
export function useSessionScope() {
  const origin = useRef(sessionSignal());
  const lifetime = useRef(new AbortController());
  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    return () => controller.abort();
  }, []);
  return useCallback(
    () => AbortSignal.any([origin.current, lifetime.current.signal]),
    [],
  );
}
