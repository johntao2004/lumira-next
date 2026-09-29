import { ApiError } from "./client.ts";
import { isMessageKey, type MessageKey } from "../i18n/messages.ts";

/** Stable server error code for feature-level message mapping. */
export function errorCode(error: unknown): string {
  return typeof error === "string" ? error : error instanceof ApiError || error instanceof Error ? error.message : "";
}

/** Map a feature's allow-list without scattering unsafe MessageKey casts. */
export function messageKeyFor(
  error: unknown,
  allowed: readonly MessageKey[],
  fallback: MessageKey = "unavailable",
  aliases: Readonly<Record<string, MessageKey>> = {},
): MessageKey {
  const code = errorCode(error);
  if (Object.hasOwn(aliases, code)) return aliases[code];
  if (isMessageKey(code) && allowed.includes(code)) return code;
  return error instanceof ApiError && error.status === 403 ? "forbidden" : fallback;
}

export function isApiErrorStatus(error: unknown, status: number): boolean {
  return error instanceof ApiError && error.status === status;
}
