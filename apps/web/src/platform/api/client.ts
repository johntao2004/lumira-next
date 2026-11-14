/**
 * 统一请求凭据、CSRF、授权版本及错误处理并丢弃旧会话响应，服务端授权版本用于一致性检查而非客户端授予权限。
 * Unify credentials, CSRF, authorization revisions and errors while discarding stale-session responses, using server revisions for consistency checks rather than client-issued grants.
 */
import type { AuthorizationContext, LoginResponse } from "../auth/api-types";
import { acceptSessionIdentity, currentSessionRevision, invalidateSession, sessionSignal } from "../auth/session-lifecycle.ts";

// 角色预览状态有意只保存在内存中；刷新页面后，先由 /auth/me 确立当前修订，再发送应用请求。
// Role-preview state is intentionally memory-only. Reloading the page causes
// /auth/me to establish the current revision before application requests run.
let authorizationContext: AuthorizationContext | null = null;
let authorizationContextLoaded = false;
let authorizationBootstrap: { revision: number; promise: Promise<void> } | null = null;
const authorizationContextListeners = new Set<() => void>();
const authorizationRevisionHeader = "X-Lumira-Authorization-Context-Revision";
let lastBlockedRefreshContextKey: string | null = null;
let lastStaleRefreshRevision: string | null = null;

function authorizationContextKey(context: AuthorizationContext | null | undefined) {
  const preview = context?.preview;
  return JSON.stringify([
    context?.rolePreviewSupported ?? false,
    context?.revision ?? null,
    preview?.state ?? null,
    preview?.roleId ?? null,
    preview?.roleName ?? null,
    preview?.roleVersion ?? null,
    preview?.startedAt ?? null,
    preview?.expiresAt ?? null,
    preview?.limitedByActor ?? null,
    preview?.blockedReason ?? null,
  ]);
}

export function hasAuthorizationContextChanged(
  previous: AuthorizationContext | null | undefined,
  next: AuthorizationContext | null | undefined,
) {
  return authorizationContextKey(previous) !== authorizationContextKey(next);
}

function isContextBootstrapExempt(route: string) {
  return route === "/auth/me"
    || route === "/auth/csrf"
    || route === "/auth/login"
    || route === "/auth/logout"
    || route.startsWith("/auth/")
    || route.startsWith("/public/");
}

function isAnonymousPublicRead(route: string, method: string | undefined) {
  const normalizedMethod = method?.toUpperCase() ?? "GET";
  return (route.startsWith("/public/") || route === "/auth/methods")
    && (normalizedMethod === "GET" || normalizedMethod === "HEAD");
}

async function ensureAuthorizationContext(signal?: AbortSignal) {
  if (authorizationContextLoaded) return;
  const revision = currentSessionRevision();
  if (!authorizationBootstrap || authorizationBootstrap.revision !== revision) {
    const bootstrap = { revision, promise: Promise.resolve() };
    bootstrap.promise = sessionMe<unknown>("authorization-context-bootstrap", signal).then(() => {
      if (currentSessionRevision() !== revision) throw new DOMException("Session changed", "AbortError");
      authorizationContextLoaded = true;
    }).finally(() => {
      if (authorizationBootstrap === bootstrap) authorizationBootstrap = null;
    });
    authorizationBootstrap = bootstrap;
  }
  await authorizationBootstrap.promise;
  if (currentSessionRevision() !== revision) throw new DOMException("Session changed", "AbortError");
}

export type SessionIdentityRequestSource = "authorization-context-bootstrap" | "workspace-session" | "login-page";

/**
 * 获取权威的当前身份，并可选暴露仅供 QA 使用的来源信息。
 * Fetch the authoritative current identity and optionally expose QA-only provenance.
 */
export async function sessionMe<T = unknown>(source: SessionIdentityRequestSource, signal?: AbortSignal): Promise<T> {
  const startingRevision = currentSessionRevision();
  const trace = typeof window === "undefined"
    ? undefined
    : (window as Window & { __lumiraSessionTrace?: (entry: Record<string, unknown>) => void }).__lumiraSessionTrace;
  trace?.({ event: "start", source, revision: startingRevision, at: Date.now() });
  try {
    const identity = await api<T>("/auth/me", { signal });
    const userId = identity && typeof identity === "object" && "userId" in identity
      ? (identity as { userId?: unknown }).userId
      : undefined;
    trace?.({ event: "success", source, startingRevision, currentRevision: currentSessionRevision(), userId, at: Date.now() });
    return identity;
  } catch (error) {
    trace?.({
      event: "error",
      source,
      startingRevision,
      currentRevision: currentSessionRevision(),
      errorName: error instanceof Error ? error.name : "unknown",
      at: Date.now(),
    });
    throw error;
  }
}

function assertActiveSessionEpoch(revision: number, sessionBound: boolean, signal: AbortSignal) {
  signal.throwIfAborted();
  if (sessionBound && currentSessionRevision() !== revision) {
    throw new DOMException("Session changed", "AbortError");
  }
}

async function captureAuthorizationRevision(route: string, signal?: AbortSignal) {
  if (isContextBootstrapExempt(route)) return null;
  if (!authorizationContextLoaded && !isContextBootstrapExempt(route)) {
    await ensureAuthorizationContext(signal);
  }
  signal?.throwIfAborted();
  return authorizationContext?.rolePreviewSupported ? authorizationContext.revision : null;
}

function dispatchAuthorizationContextRefresh() {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event("lumira:authorization-context-refresh"));
  }
}

function broadcastAuthorizationContextRevision() {
  if (typeof window === "undefined" || typeof BroadcastChannel === "undefined") return;
  try {
    const channel = new BroadcastChannel("lumira:authorization-context");
    channel.postMessage({ revision: authorizationContext?.revision ?? null });
    channel.close();
  } catch {
    // 通道关闭或不可用时，只失去跨标签页刷新提示。
    // A closed or unavailable channel only removes the cross-tab refresh hint.
  }
}

export function getAuthorizationContext() {
  return authorizationContext;
}

export function subscribeAuthorizationContext(listener: () => void) {
  authorizationContextListeners.add(listener);
  return () => { authorizationContextListeners.delete(listener); };
}

/**
 * 服务端会话处于任意角色预览上下文时返回 true。
 * True while the server-backed session is operating in any role-preview context.
 */
export function isRolePreviewReadOnly() {
  return authorizationContext?.preview !== null && authorizationContext?.preview !== undefined;
}

export function setAuthorizationContext(context: AuthorizationContext | null) {
  const changed = hasAuthorizationContextChanged(authorizationContext, context);
  if (changed) {
    lastBlockedRefreshContextKey = null;
    lastStaleRefreshRevision = null;
  }
  authorizationContext = context;
  authorizationContextLoaded = true;
  if (changed) for (const listener of authorizationContextListeners) listener();
}

export function clearAuthorizationContext() {
  const changed = hasAuthorizationContextChanged(authorizationContext, null);
  if (changed) {
    lastBlockedRefreshContextKey = null;
    lastStaleRefreshRevision = null;
  }
  authorizationContext = null;
  authorizationContextLoaded = false;
  if (changed) for (const listener of authorizationContextListeners) listener();
}

export function shouldRefreshAuthorizationContextRevision(
  incomingRevision: string | null,
  lastSeenRevision: string | null,
  currentRevision: string | null,
) {
  return incomingRevision !== lastSeenRevision && incomingRevision !== currentRevision;
}

export class ApiError extends Error {
  status: number;
  issues: {row:number;field:string;reason:string}[];
  retryAfterSeconds: number | null;
  constructor(
    status: number,
    message: string,
    issues: {row:number;field:string;reason:string}[] = [],
    retryAfterSeconds: number | null = null,
  ) {
    super(message);
    this.status = status;
    this.issues = issues;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
type SuccessResponseDecoder<T> = (response: Response) => Promise<T> | T;
const DEFAULT_API_TIMEOUT_MS = 15_000;
const MAX_API_TIMEOUT_MS = 600_000;

function requestTimeout(timeoutMs: number) {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > MAX_API_TIMEOUT_MS) {
    throw new RangeError(`timeoutMs must be an integer between 1 and ${MAX_API_TIMEOUT_MS}`);
  }
  return timeoutMs;
}

export async function api<T>(
  path: string,
  init: RequestInit = {},
  decodeSuccess?: SuccessResponseDecoder<T>,
  timeoutMs = DEFAULT_API_TIMEOUT_MS,
): Promise<T> {
  const boundedTimeoutMs = requestTimeout(timeoutMs);
  const route = path.split("?", 1)[0];
  const anonymousPublicRead = isAnonymousPublicRead(route, init.method);
  const policyMethod = init.method?.toUpperCase() ?? "GET";
  const publicPolicyRead = route === "/auth/methods"
    && (policyMethod === "GET" || policyMethod === "HEAD");
  const sessionBound = !route.startsWith("/public/") && !publicPolicyRead;
  const identityBootstrap = route === "/auth/me";
  const startingSessionRevision = currentSessionRevision();
  const signals = [
    // /auth/me 响应可能确立不同的服务端身份；仍需校验修订，但不能将该请求绑定到接受新身份时必须撤销的旧会话控制器。
    // The /auth/me response may establish a different server identity. Keep it
    // revision-checked below, but don't bind its own transport to the session
    // controller that accepting that identity must retire.
    ...(sessionBound && !identityBootstrap ? [sessionSignal()] : []),
    ...(init.signal ? [init.signal] : []),
    AbortSignal.timeout(boundedTimeoutMs),
  ];
  const requestSignal = AbortSignal.any(signals);
  assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
  if (!authorizationContextLoaded && !isContextBootstrapExempt(route)) {
    await ensureAuthorizationContext(requestSignal);
  }
  assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
  const headers = new Headers(init.headers);
  const revision = authorizationContext?.rolePreviewSupported ? authorizationContext.revision : null;
  if (anonymousPublicRead) {
    headers.delete(authorizationRevisionHeader);
  } else if (revision && route !== "/auth/login" && route !== "/auth/logout" && route !== "/auth/csrf"
    && !headers.has(authorizationRevisionHeader)) {
    headers.set(authorizationRevisionHeader, revision);
  }
  const response = await fetch(`/api${path}`, {
    ...init,
    headers,
    credentials: anonymousPublicRead ? "omit" : "same-origin",
    cache: "no-store",
    signal: requestSignal,
  });
  // 某些传输或测试、浏览器拦截层在中止后仍可能完成；这些迟到响应绝不能更新会话身份或界面状态。
  // Some transports and test/browser interception layers can resolve after an
  // abort. Never apply such a late response to session identity or UI state.
  assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
  if (!response.ok) {
    let issues: {row:number;field:string;reason:string}[] = [];
    // 传输错误保持语言中立；页面通过共享文案目录映射稳定 API 错误码，避免底层客户端固定输出某一种语言。
    // Keep transport errors locale-neutral. Pages map stable API error codes
    // through the shared message catalog instead of leaking one language from
    // the low-level client.
    let message = "unavailable";
    try {
      const body = await response.json();
      assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
      message = body.detail ?? body.message ?? message;
      if(Array.isArray(body.issues))issues=body.issues.slice(0,100).filter((item:unknown):item is {row:number;field:string;reason:string}=>{
        if(!item||typeof item!=="object")return false;
        const value=item as Record<string,unknown>;
        return Number.isInteger(value.row)&&Number(value.row)>0&&typeof value.field==="string"&&typeof value.reason==="string";
      });
    } catch {
      assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
    }
    assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
    if (response.status === 401 && path !== "/auth/login" && !route.startsWith("/public/") && typeof window !== "undefined") {
      clearAuthorizationContext();
      invalidateSession(true);
      window.dispatchEvent(new Event("lumira:session-expired"));
    }
    if(message==="moduleUnavailable"&&typeof window!=="undefined")window.dispatchEvent(new Event("lumira:modules-changed"));
    if (response.status === 409 && message === "authorizationContextChanged" && typeof window !== "undefined") {
      const requestRevision = headers.get(authorizationRevisionHeader);
      const currentRevision = authorizationContext?.revision ?? null;
      if (requestRevision && requestRevision === currentRevision && lastStaleRefreshRevision !== requestRevision) {
        lastStaleRefreshRevision = requestRevision;
        dispatchAuthorizationContextRefresh();
      }
    }
    if (response.status === 403 && message === "rolePreviewBlocked" && typeof window !== "undefined") {
      const contextKey = authorizationContextKey(authorizationContext);
      if (authorizationContext?.preview?.state !== "BLOCKED" && lastBlockedRefreshContextKey !== contextKey) {
        lastBlockedRefreshContextKey = contextKey;
        dispatchAuthorizationContextRefresh();
      }
    }
    const retryAfter = response.headers.get("Retry-After");
    const retryAfterSeconds = retryAfter
      ? Number.isFinite(Number(retryAfter))
        ? Math.max(0, Math.ceil(Number(retryAfter)))
        : Math.max(0, Math.ceil((Date.parse(retryAfter) - Date.now()) / 1000))
      : null;
    throw new ApiError(response.status, message, issues, Number.isFinite(retryAfterSeconds) ? retryAfterSeconds : null);
  }
  if (response.status === 204) {
    if (route === "/account/role-preview" && init.method?.toUpperCase() === "DELETE") {
      const previousRevision = authorizationContext?.revision ?? null;
      await api("/auth/me");
      if (previousRevision !== (authorizationContext?.revision ?? null)) {
        dispatchAuthorizationContextRefresh();
        broadcastAuthorizationContextRevision();
      }
    }
    return undefined as T;
  }
  if (decodeSuccess) {
    const decoded = await decodeSuccess(response);
    assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
    return decoded;
  }
  const body = await response.text();
  assertActiveSessionEpoch(startingSessionRevision, sessionBound, requestSignal);
  const result = body ? JSON.parse(body) as T : undefined as T;
  if (route === "/auth/me" && result && typeof result === "object") {
    const session = result as { userId?: unknown; authorizationContext?: AuthorizationContext };
    if (typeof session.userId !== "string" || !session.userId) {
      throw new ApiError(500, "invalidSessionIdentity");
    }
    acceptSessionIdentity(session.userId);
    const context = session.authorizationContext;
    setAuthorizationContext(context ?? null);
  } else if (route.startsWith("/account/role-preview") && result && typeof result === "object") {
    const value = result as Partial<AuthorizationContext> & { authorizationContext?: AuthorizationContext };
    const context = value.authorizationContext ?? (typeof value.rolePreviewSupported === "boolean" ? value as AuthorizationContext : null);
    if (context) {
      const previousRevision = authorizationContext?.revision ?? null;
      setAuthorizationContext(context);
      const method = init.method?.toUpperCase() ?? "GET";
      if ((method === "POST" || method === "DELETE") && previousRevision !== (context.revision ?? null)) {
        dispatchAuthorizationContextRefresh();
        broadcastAuthorizationContextRevision();
      }
    }
  }
  return result;
}
/**
 * 二进制读取与 JSON 请求共用授权头、取消机制、凭据和 API 错误处理。
 * Binary reads share authorization headers, cancellation, credentials and API errors with JSON requests.
 */
export function apiBlob(path: string, init: RequestInit = {}, timeoutMs = DEFAULT_API_TIMEOUT_MS) {
  return api<Blob>(path, init, response => response.blob(), timeoutMs);
}
export async function csrf(signal?: AbortSignal) {
  return api<{ headerName: string; token: string }>("/auth/csrf", { signal });
}
/**
 * 二进制写入与 JSON 变更共用认证、CSRF 及 API 错误处理。
 * Binary writes share authentication, CSRF and API error handling with JSON mutations.
 */
export async function upload<T>(path: string, file: Blob, originSignal?: AbortSignal): Promise<T> {
  const route = path.split("?", 1)[0];
  const requestSignal = AbortSignal.any([
    sessionSignal(),
    ...(originSignal ? [originSignal] : []),
    AbortSignal.timeout(15_000),
  ]);
  requestSignal.throwIfAborted();
  const revision = await captureAuthorizationRevision(route, requestSignal);
  const token = await csrf(requestSignal);
  requestSignal.throwIfAborted();
  const headers = new Headers({ "Content-Type": "application/octet-stream", [token.headerName]: token.token });
  if (revision) headers.set(authorizationRevisionHeader, revision);
  return api<T>(path, { method: "POST", headers, body: file, signal: requestSignal });
}
export async function login(username: string, password: string, captchaProofId?: string) {
  const requestSignal = AbortSignal.any([sessionSignal(), AbortSignal.timeout(15_000)]);
  const token = await csrf(requestSignal);
  const body = new URLSearchParams({ username, password });
  if (captchaProofId) body.set("captchaProofId", captchaProofId);
  return api<LoginResponse>("/auth/login", {
    method: "POST",
    signal: requestSignal,
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      [token.headerName]: token.token,
    },
    body,
  });
}
export async function logout() {
  const requestSignal = AbortSignal.any([sessionSignal(), AbortSignal.timeout(15_000)]);
  const token = await csrf(requestSignal);
  const result = await api<void>("/auth/logout", {
    method: "POST",
    headers: { [token.headerName]: token.token },
    signal: requestSignal,
  });
  clearAuthorizationContext();
  invalidateSession(true);
  return result;
}
/**
 * 共享写传输：所有平台变更均获取新的 CSRF 令牌。
 * Shared write transport: all platform mutations obtain a fresh CSRF token.
 */
export async function mutate<T>(path: string, method: "POST" | "PUT" | "PATCH" | "DELETE", data?: unknown, signal?: AbortSignal, timeoutMs = 15_000): Promise<T> {
  const route = path.split("?", 1)[0];
  const requestSignal = AbortSignal.any([
    sessionSignal(),
    ...(signal ? [signal] : []),
    AbortSignal.timeout(timeoutMs),
  ]);
  requestSignal.throwIfAborted();
  const revision = await captureAuthorizationRevision(route, requestSignal);
  const token = await csrf(requestSignal);
  requestSignal.throwIfAborted();
  const headers = new Headers({ "Content-Type": "application/json", [token.headerName]: token.token });
  if (revision) headers.set(authorizationRevisionHeader, revision);
  return api<T>(path, { method, headers, body: data === undefined ? undefined : JSON.stringify(data), signal: requestSignal });
}
