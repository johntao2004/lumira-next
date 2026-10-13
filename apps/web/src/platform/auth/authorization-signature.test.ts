import test from "node:test";
import assert from "node:assert/strict";
import {
  effectiveAuthorizationSignature,
  runtimeNavigationQueryKey,
} from "./authorization-signature.ts";

const session = {
  userId: "user-1",
  authorities: ["platform:feedback:access", "platform:feedback:read"],
  globalAuthorities: ["platform:feedback:access"],
  enabledModules: ["feedback"],
  authorizationRevision: "authz-1",
  authorizationContext: { rolePreviewSupported: true, revision: null, preview: null },
};

test("grant and revoke produce a new runtime navigation key for the same session", () => {
  const initialKey = runtimeNavigationQueryKey(session, 4);
  const granted = {
    ...session,
    authorities: [...session.authorities, "platform:feedback:create"],
    globalAuthorities: [...session.globalAuthorities, "platform:feedback:create"],
    authorizationRevision: "authz-2",
  };
  const grantedKey = runtimeNavigationQueryKey(granted, 4);
  const revokedKey = runtimeNavigationQueryKey(session, 4);

  assert.notDeepEqual(grantedKey, initialKey);
  assert.deepEqual(revokedKey, initialKey);
  assert.notEqual(effectiveAuthorizationSignature(granted), effectiveAuthorizationSignature(session));
});

test("scope-only authorization revisions refresh the same user's queries", () => {
  const changedScope = { ...session, authorizationRevision: "authz-scope-2" };

  assert.deepEqual(changedScope.authorities, session.authorities);
  assert.deepEqual(changedScope.globalAuthorities, session.globalAuthorities);
  assert.notEqual(effectiveAuthorizationSignature(changedScope), effectiveAuthorizationSignature(session));
  assert.notDeepEqual(runtimeNavigationQueryKey(changedScope, 4), runtimeNavigationQueryKey(session, 4));
});

test("grant-set ordering and unrelated profile changes do not change the key", () => {
  const reordered = {
    ...session,
    authorities: [...session.authorities].reverse(),
    globalAuthorities: [...session.globalAuthorities].reverse(),
    enabledModules: [...session.enabledModules].reverse(),
  };
  const profileOnly = { ...session, displayName: "Updated name" };

  assert.equal(effectiveAuthorizationSignature(reordered), effectiveAuthorizationSignature(session));
  assert.deepEqual(runtimeNavigationQueryKey(reordered, 4), runtimeNavigationQueryKey(session, 4));
  assert.equal(effectiveAuthorizationSignature(profileOnly), effectiveAuthorizationSignature(session));
});

test("runtime navigation cache keys stay isolated across users and session epochs", () => {
  const sameGrantsForAnotherUser = { ...session, userId: "user-2" };

  assert.notDeepEqual(
    runtimeNavigationQueryKey(sameGrantsForAnotherUser, 4),
    runtimeNavigationQueryKey(session, 4),
  );
  assert.notDeepEqual(runtimeNavigationQueryKey(session, 5), runtimeNavigationQueryKey(session, 4));
});
