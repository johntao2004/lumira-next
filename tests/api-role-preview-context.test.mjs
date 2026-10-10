import assert from "node:assert/strict";
import test from "node:test";
import {
  api,
  ApiError,
  clearAuthorizationContext,
  getAuthorizationContext,
  hasAuthorizationContextChanged,
  isRolePreviewReadOnly,
  mutate,
  setAuthorizationContext,
  shouldRefreshAuthorizationContextRevision,
  upload,
} from "../apps/web/src/platform/api/client.ts";

const context = (revision, preview = null) => ({ revision, rolePreviewSupported: true, preview });

test("role-preview read-only helper covers active and blocked server contexts only", () => {
  setAuthorizationContext(context("revision-none"));
  assert.equal(isRolePreviewReadOnly(), false);
  setAuthorizationContext(context("revision-active", { state: "ACTIVE" }));
  assert.equal(isRolePreviewReadOnly(), true);
  setAuthorizationContext(context("revision-blocked", { state: "BLOCKED" }));
  assert.equal(isRolePreviewReadOnly(), true);
  setAuthorizationContext({ revision: null, rolePreviewSupported: false, preview: null });
  assert.equal(isRolePreviewReadOnly(), false);
  setAuthorizationContext({ revision: null, rolePreviewSupported: false, preview: { state: "ACTIVE" } });
  assert.equal(isRolePreviewReadOnly(), true);
});

function captureAuthorizationEvents(t) {
  const events = [];
  const messages = [];
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  const originalBroadcastChannel = Object.getOwnPropertyDescriptor(globalThis, "BroadcastChannel");
  class CapturingBroadcastChannel {
    constructor(name) { this.name = name; }
    postMessage(message) { messages.push({ channel: this.name, message }); }
    close() {}
  }
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: { dispatchEvent(event) { events.push(event.type); } },
  });
  Object.defineProperty(globalThis, "BroadcastChannel", {
    configurable: true,
    writable: true,
    value: CapturingBroadcastChannel,
  });
  t.after(() => {
    if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow);
    else delete globalThis.window;
    if (originalBroadcastChannel) Object.defineProperty(globalThis, "BroadcastChannel", originalBroadcastChannel);
    else delete globalThis.BroadcastChannel;
  });
  return { events, messages };
}

test("role-preview GET refreshes memory without emitting invalidation or broadcast", async t => {
  const notifications = captureAuthorizationEvents(t);
  const nextContext = context("revision-2");
  setAuthorizationContext(context("revision-1"));
  t.mock.method(globalThis, "fetch", async () => Response.json({ authorizationContext: nextContext, items: [] }));

  await api("/account/role-preview/candidates?page=1&pageSize=20");

  assert.equal(getAuthorizationContext()?.revision, "revision-2");
  assert.deepEqual(notifications.events, []);
  assert.deepEqual(notifications.messages, []);
});

test("public reads omit session credentials and revision while private and auth requests retain them", async t => {
  t.after(() => clearAuthorizationContext());
  setAuthorizationContext(context("revision-authenticated"));
  const requests = [];
  t.mock.method(globalThis, "fetch", async (url, init = {}) => {
    requests.push({
      url,
      method: init.method?.toUpperCase() ?? "GET",
      credentials: init.credentials,
      revision: new Headers(init.headers).get("X-Lumira-Authorization-Context-Revision"),
    });
    return ["HEAD", "POST"].includes(init.method?.toUpperCase())
      ? new Response(null, { status: 204 })
      : Response.json([]);
  });

  await api("/public/localization/locales");
  await api("/public/branding", { method: "HEAD" });
  await api("/public/contract-probe", { method: "POST" });
  await api("/account/locale");
  await api("/auth/captcha/challenge");

  assert.deepEqual(requests, [
    { url: "/api/public/localization/locales", method: "GET", credentials: "omit", revision: null },
    { url: "/api/public/branding", method: "HEAD", credentials: "omit", revision: null },
    { url: "/api/public/contract-probe", method: "POST", credentials: "same-origin", revision: "revision-authenticated" },
    { url: "/api/account/locale", method: "GET", credentials: "same-origin", revision: "revision-authenticated" },
    { url: "/api/auth/captcha/challenge", method: "GET", credentials: "same-origin", revision: "revision-authenticated" },
  ]);
});

test("role-preview POST notifies once when its authorization revision changes", async t => {
  const notifications = captureAuthorizationEvents(t);
  const nextContext = context("revision-2", {
    state: "ACTIVE", roleId: "role-1", roleName: "Preview", roleVersion: 1,
    startedAt: "2026-10-04T00:00:00Z", expiresAt: "2026-10-04T00:10:00Z",
    limitedByActor: false, blockedReason: null,
  });
  setAuthorizationContext(context("revision-1"));
  t.mock.method(globalThis, "fetch", async () => Response.json({ authorizationContext: nextContext }));

  await api("/account/role-preview", { method: "POST", body: "{}" });

  assert.deepEqual(notifications.events, ["lumira:authorization-context-refresh"]);
  assert.deepEqual(notifications.messages, [{ channel: "lumira:authorization-context", message: { revision: "revision-2" } }]);
  assert.equal(shouldRefreshAuthorizationContextRevision("revision-2", "revision-1", "revision-1"), true);
  assert.equal(shouldRefreshAuthorizationContextRevision("revision-2", "revision-2", "revision-1"), false);
  assert.equal(shouldRefreshAuthorizationContextRevision("revision-2", "revision-1", "revision-2"), false);
});

test("blocked preview 403s on private reads request one session refresh and settle after BLOCKED context is known", async t => {
  const notifications = captureAuthorizationEvents(t);
  const active = context("revision-active", {
    state: "ACTIVE", roleId: "role-1", roleName: "Preview", roleVersion: 1,
    startedAt: "2026-10-04T00:00:00Z", expiresAt: "2026-10-04T00:10:00Z",
    limitedByActor: false, blockedReason: null,
  });
  const blocked = context("revision-blocked", {
    ...active.preview, state: "BLOCKED", blockedReason: "roleUnavailable",
  });
  setAuthorizationContext(active);
  t.mock.method(globalThis, "fetch", async () => Response.json({ detail: "rolePreviewBlocked" }, { status: 403 }));

  const firstWave = await Promise.allSettled(Array.from({ length: 20 }, (_, index) => api(`/account/locale?wave=active-${index}`)));
  assert.ok(firstWave.every(result => result.status === "rejected"));
  assert.deepEqual(notifications.events, ["lumira:authorization-context-refresh"]);

  setAuthorizationContext(blocked);
  const blockedWave = await Promise.allSettled(Array.from({ length: 20 }, (_, index) => api(`/account/locale?wave=blocked-${index}`)));
  assert.ok(blockedWave.every(result => result.status === "rejected"));
  assert.deepEqual(notifications.events, ["lumira:authorization-context-refresh"]);
  assert.equal(hasAuthorizationContextChanged(blocked, blocked), false);
  assert.equal(hasAuthorizationContextChanged(active, blocked), true);
});

test("BLOCKED preview can still exit and broadcasts the fresh non-preview context", async t => {
  const notifications = captureAuthorizationEvents(t);
  const blocked = context("revision-blocked", { state: "BLOCKED", roleId: "role-1" });
  const exited = context("revision-exited");
  setAuthorizationContext(blocked);
  t.mock.method(globalThis, "fetch", async url => {
    if (url === "/api/account/role-preview") return new Response(null, { status: 204 });
    if (url === "/api/auth/me") return Response.json({ userId: "fixture-user", authorizationContext: exited });
    throw new Error(`Unexpected request: ${url}`);
  });

  await api("/account/role-preview", { method: "DELETE" });

  assert.equal(getAuthorizationContext()?.revision, "revision-exited");
  assert.equal(isRolePreviewReadOnly(), false);
  assert.deepEqual(notifications.events, ["lumira:authorization-context-refresh"]);
  assert.deepEqual(notifications.messages, [{ channel: "lumira:authorization-context", message: { revision: "revision-exited" } }]);
});

test("mutation and upload keep the revision captured before delayed CSRF and never replay stale writes", async t => {
  setAuthorizationContext(context("revision-a"));
  const businessCalls = [];
  let csrfGate = null;
  t.mock.method(globalThis, "fetch", async (url, init) => {
    if (url === "/api/auth/csrf") {
      csrfGate.started();
      return csrfGate.response;
    }
    businessCalls.push({ url, headers: new Headers(init.headers) });
    return Response.json({ detail: "authorizationContextChanged" }, { status: 409 });
  });

  const createCsrfGate = () => {
    let release;
    let markStarted;
    const started = new Promise(resolve => { markStarted = resolve; });
    const response = new Promise(resolve => { release = resolve; });
    return {
      started: markStarted,
      response,
      release: () => release(Response.json({ headerName: "X-CSRF-TOKEN", token: "fresh-token" })),
      whenStarted: started,
    };
  };

  csrfGate = createCsrfGate();
  const mutation = mutate("/account/role-preview", "POST", { roleId: "role-1" });
  await csrfGate.whenStarted;
  setAuthorizationContext(context("revision-b"));
  csrfGate.release();
  await assert.rejects(mutation, error => error instanceof ApiError && error.status === 409);

  csrfGate = createCsrfGate();
  const binaryUpload = upload("/account/profile/avatar", new Blob(["image"]));
  await csrfGate.whenStarted;
  setAuthorizationContext(context("revision-c"));
  csrfGate.release();
  await assert.rejects(binaryUpload, error => error instanceof ApiError && error.status === 409);

  assert.equal(businessCalls.length, 2);
  assert.deepEqual(businessCalls.map(call => call.headers.get("X-Lumira-Authorization-Context-Revision")), ["revision-a", "revision-b"]);
  assert.ok(businessCalls.every(call => call.headers.get("X-CSRF-TOKEN") === "fresh-token"));
});
