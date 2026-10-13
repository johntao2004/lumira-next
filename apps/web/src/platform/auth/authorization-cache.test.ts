import test from "node:test";
import assert from "node:assert/strict";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { refreshAuthorizationScopedQueries } from "./authorization-cache.ts";

const navigationKey = ["navigation-runtime", "user-1", 8, "revision-2"] as const;
const oldNavigationKey = ["navigation-runtime", "user-1", 8, "revision-1"] as const;
const usersKey = ["users", "list"] as const;

function createClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

async function waitFor(predicate: () => boolean) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 0));
  }
  assert.fail("Timed out waiting for the query state transition");
}

test("scope refresh cancels late reads, clears old rows before refetch, and preserves session and current navigation", async () => {
  const client = createClient();
  client.setQueryData(usersKey, { items: ["old-scope-row"] });
  client.setQueryData(["session"], { userId: "user-1" });
  client.setQueryData(oldNavigationKey, { items: ["old-menu"] });
  client.setQueryData(navigationKey, { items: ["current-menu"] });

  let resolveWrite: ((value: string) => void) | undefined;
  const write = client.getMutationCache().build(client, {
    mutationFn: () => new Promise<string>(resolve => { resolveWrite = resolve; }),
  });
  const writePromise = write.execute(undefined);
  void writePromise.catch(() => {});
  await waitFor(() => resolveWrite !== undefined);

  let calls = 0;
  let resolveOld!: (value: { items: string[] }) => void;
  let resolveNarrow!: (value: { items: string[] }) => void;
  const observer = new QueryObserver(client, {
    queryKey: usersKey,
    staleTime: Infinity,
    queryFn: ({ signal }) => {
      void signal;
      calls += 1;
      if (calls === 1) return new Promise(resolve => { resolveOld = resolve; });
      if (calls === 2) return new Promise(resolve => { resolveNarrow = resolve; });
      throw new Error("Unexpected extra users query");
    },
  });
  const unsubscribe = observer.subscribe(() => {});
  void observer.refetch().catch(() => {});
  await waitFor(() => calls === 1);

  const refreshing = refreshAuthorizationScopedQueries(client, navigationKey);
  await waitFor(() => calls === 2);

  assert.equal(observer.getCurrentResult().data, undefined);
  assert.equal(client.getQueryData<{ userId: string }>(["session"] as const)?.userId, "user-1");
  assert.deepEqual(client.getQueryData(navigationKey), { items: ["current-menu"] });
  assert.equal(client.getQueryState(oldNavigationKey)?.data, undefined);
  assert.equal(client.isMutating(), 1);

  resolveOld({ items: ["late-old-scope-row"] });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(observer.getCurrentResult().data, undefined);

  resolveNarrow({ items: ["narrow-scope-row"] });
  await refreshing;
  assert.deepEqual(observer.getCurrentResult().data, { items: ["narrow-scope-row"] });
  assert.ok(resolveWrite);
  resolveWrite("saved");
  await writePromise;
  assert.equal(client.isMutating(), 0);

  unsubscribe();
  observer.destroy();
  client.clear();
});

test("a forbidden refetch leaves no previously cached rows to render", async () => {
  const client = createClient();
  client.setQueryData(usersKey, { items: ["row-from-old-scope"] });

  let calls = 0;
  let resolveOld!: (value: { items: string[] }) => void;
  const observer = new QueryObserver(client, {
    queryKey: usersKey,
    staleTime: Infinity,
    queryFn: ({ signal }) => {
      void signal;
      calls += 1;
      if (calls === 1) return new Promise(resolve => { resolveOld = resolve; });
      const error = Object.assign(new Error("forbidden"), { status: 403 });
      return Promise.reject(error);
    },
  });
  const unsubscribe = observer.subscribe(() => {});
  void observer.refetch().catch(() => {});
  await waitFor(() => calls === 1);

  await refreshAuthorizationScopedQueries(client, navigationKey);
  const result = observer.getCurrentResult();
  assert.equal(result.isError, true);
  assert.equal(result.data, undefined);
  assert.equal((result.error as Error & { status: number }).status, 403);

  resolveOld({ items: ["late-old-scope-row"] });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(observer.getCurrentResult().data, undefined);

  unsubscribe();
  observer.destroy();
  client.clear();
});

test("authorization refresh preserves shared public configuration while clearing protected data", async () => {
  const client = createClient();
  const branding = { websiteName: "Lumira", websiteFaviconUrl: "/favicon.ico" };
  const watermark = { enabled: true, text: "Lumira" };
  const locales = ["zh-CN", "en"];
  const authMethods = { capabilities: { password: true } };
  const passwordPolicy = { passwordRules: { minLength: 12 } };
  client.setQueryData(["public-branding"], branding);
  client.setQueryData(["public-watermark"], watermark);
  client.setQueryData(["public-localization-locales"], locales);
  client.setQueryData(["auth-methods"], authMethods);
  client.setQueryData(["password-policy"], passwordPolicy);
  client.setQueryData(usersKey, { items: ["old-scope-row"] });

  await refreshAuthorizationScopedQueries(client, navigationKey);

  assert.deepEqual(client.getQueryData(["public-branding"]), branding);
  assert.deepEqual(client.getQueryData(["public-watermark"]), watermark);
  assert.deepEqual(client.getQueryData(["public-localization-locales"]), locales);
  assert.deepEqual(client.getQueryData(["auth-methods"]), authMethods);
  assert.deepEqual(client.getQueryData(["password-policy"]), passwordPolicy);
  assert.equal(client.getQueryState(usersKey)?.data, undefined);

  client.clear();
});
