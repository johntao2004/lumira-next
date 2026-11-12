import assert from "node:assert/strict";
import test from "node:test";
import { ApiError } from "../../../../apps/web/src/platform/api/client.ts";
import { messageKeyFor } from "../../../../apps/web/src/platform/api/errors.ts";
import { messages } from "../../../../apps/web/src/platform/i18n/messages/zh-cn.ts";
import { roleErrorKeys } from "./role-errors.ts";

test("role save preserves specific delegation and registration errors", () => {
  for (const [status, code] of [
    [403, "rolePermissionDelegationDenied"],
    [403, "rolePermissionUnavailable"],
    [409, "registrationRoleInUse"],
  ] as const) {
    const key = messageKeyFor(new ApiError(status, code), roleErrorKeys);
    assert.equal(key, code);
    assert.ok(messages[key].length > 0);
    assert.doesNotMatch(messages[key], /令牌.*过期/);
  }
});

test("unrelated authorization errors retain the existing fallback", () => {
  assert.equal(messageKeyFor(new ApiError(403, "otherDenial"), roleErrorKeys), "forbidden");
});
