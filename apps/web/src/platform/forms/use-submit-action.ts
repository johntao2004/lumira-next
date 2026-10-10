import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { usePreferences } from "../providers";
import { messageKeyFor } from "../api/errors";
import type { MessageKey } from "../i18n/messages";

export type SubmitOptions = {
  errorKeys?: readonly MessageKey[];
  errorAliases?: Readonly<Record<string, MessageKey>>;
  fallback?: MessageKey;
  mapError?: (cause: unknown) => MessageKey;
  onError?: (key: MessageKey, cause: unknown) => void | Promise<void>;
};

/** One in-flight operation, including confirmation and post-save refresh. */
export function useSubmitAction(options: SubmitOptions = {}) {
  const { t } = usePreferences();
  const busy = useRef(false);
  const mounted = useRef(true);
  const [pending, setPending] = useState(false);
  const [error, setErrorState] = useState<MessageKey | null>(null);
  const [retryAfterSeconds, setRetryAfterSeconds] = useState<number | null>(null);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  async function run(operation: () => Promise<void | boolean>): Promise<boolean> {
    // A ref also blocks submissions within the same render, before pending updates.
    if (busy.current) return false;
    busy.current = true;
    setPending(true);
    setErrorState(null);
    setRetryAfterSeconds(null);
    try {
      return (await operation()) !== false;
    } catch (cause) {
      const key = options.mapError?.(cause)
        ?? messageKeyFor(cause, options.errorKeys ?? [], options.fallback, options.errorAliases);
      if (mounted.current) {
        setRetryAfterSeconds(cause instanceof ApiError && cause.message === "passwordHashCapacity"
          ? cause.retryAfterSeconds ?? 1
          : null);
        setErrorState(key);
        await options.onError?.(key, cause);
      }
      return false;
    } finally {
      busy.current = false;
      if (mounted.current) setPending(false);
    }
  }

  function setError(value: MessageKey | null) {
    setErrorState(value);
    if (value !== "passwordHashCapacity") setRetryAfterSeconds(null);
  }

  const errorMessage = error
    ? t(error, error === "passwordHashCapacity" ? { seconds: retryAfterSeconds ?? 1 } : undefined)
    : undefined;

  return { pending, error, errorMessage, retryAfterSeconds, setError, run, isPending: () => busy.current };
}
