/**
 * A session epoch belongs to one server-confirmed user identity. The epoch
 * signal retires requests; cross-tab storage values are opaque nonces only and
 * never carry an identity or credential.
 */
export const SESSION_STORAGE_KEY = "lumira:session-invalidated";

export type SessionBoundary = {
  revision: number;
  kind: "identity" | "invalidated";
  userId: string | null;
};

let activeUserId: string | undefined;
let lastKnownUserId: string | undefined;
let revision = 0;
let controller = new AbortController();
const listeners = new Set<(boundary: SessionBoundary) => void>();

export function sessionSignal(): AbortSignal {
  return controller.signal;
}

export function currentSessionRevision(): number {
  return revision;
}

export function currentSessionUserId(): string | undefined {
  return activeUserId;
}

export function subscribeSessionBoundary(listener: (boundary: SessionBoundary) => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function emit(kind: SessionBoundary["kind"], userId: string | null) {
  const boundary = { revision: ++revision, kind, userId };
  for (const listener of listeners) listener(boundary);
}

function nonce() {
  const bytes = new Uint8Array(16);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    crypto.getRandomValues(bytes);
    return [...bytes].map(value => value.toString(16).padStart(2, "0")).join("");
  }
  // The value is only a change notification. It is never used as a secret.
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function broadcastInvalidation() {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(SESSION_STORAGE_KEY, nonce());
  } catch {
    // Storage can be unavailable; focus revalidation remains available.
  }
}

/** Notify other tabs after a completed login, without broadcasting identity data. */
export function announceSessionChangeAcrossTabs() {
  broadcastInvalidation();
}

function retire(nextUserId: string | undefined, kind: SessionBoundary["kind"]) {
  const previous = controller;
  controller = new AbortController();
  activeUserId = nextUserId;
  if (nextUserId !== undefined) lastKnownUserId = nextUserId;
  previous.abort();
  emit(kind, nextUserId ?? null);
}

/** Accept identity only from the server's authenticated /auth/me response. */
export function acceptSessionIdentity(nextUserId: string) {
  if (!nextUserId) throw new TypeError("A stable server userId is required");
  if (activeUserId === nextUserId) return false;
  const previousUserId = activeUserId ?? lastKnownUserId;
  if (previousUserId === undefined) {
    activeUserId = nextUserId;
    lastKnownUserId = nextUserId;
    return true;
  }
  retire(nextUserId, "identity");
  if (previousUserId !== undefined && previousUserId !== nextUserId) broadcastInvalidation();
  return true;
}

/** Retire a request/cache epoch without trusting any identity supplied by another tab. */
export function invalidateSession(broadcast = false) {
  const hadActiveIdentity = activeUserId !== undefined;
  retire(undefined, "invalidated");
  if (broadcast && hadActiveIdentity) broadcastInvalidation();
  if (typeof window !== "undefined") window.dispatchEvent(new Event("lumira:session-invalidated"));
}
