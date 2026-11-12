import test from "node:test";
import assert from "node:assert/strict";
import { safeNotificationHref } from "../plugins/notifications/frontend/safe-notification-href.ts";

const base = "https://lumira.invalid/dashboard/home";

test("historical external, control, and path-backslash targets become non-links", () => {
  for (const value of [
    null,
    undefined,
    {},
    "",
    "https://outside.invalid",
    "//outside.invalid",
    "/\\outside.invalid",
    "/\t/outside.invalid",
    "/\t\\outside.invalid",
    "/\r/outside.invalid",
    "/\n/outside.invalid",
    "/folder\\child",
    " /dashboard/home",
  ]) {
    assert.equal(safeNotificationHref(value), null, JSON.stringify(value));
  }

  for (let code = 0; code <= 0x9f; code++) {
    if (code <= 0x1f || code >= 0x7f) {
      assert.equal(
        safeNotificationHref(`/folder${String.fromCharCode(code)}/child`),
        null,
        `control U+${code.toString(16).padStart(4, "0")}`,
      );
    }
  }
});

test("internal paths preserve Unicode, encoding, query, fragment, and safe backslashes", () => {
  for (const value of [
    "/",
    "/dashboard/feedback",
    "/custom/页面?q=x#section",
    "/%2f%5c%09safe",
    "/dashboard/home?q=\\raw",
    "/dashboard/home#\\raw",
    "/dashboard/home?next=https%3A%2F%2Foutside.invalid",
  ]) {
    assert.equal(safeNotificationHref(value), value);
    assert.equal(new URL(value, base).origin, new URL(base).origin);
  }

  assert.equal(safeNotificationHref(`/${"x".repeat(511)}`)?.length, 512);
  assert.equal(safeNotificationHref(`/${"x".repeat(512)}`), null);
});

test("accepted two-character ASCII prefixes cannot change the URL origin", () => {
  for (let first = 0; first < 128; first++) {
    for (let second = 0; second < 128; second++) {
      const value = `/${String.fromCharCode(first, second)}outside.invalid/check`;
      const safe = safeNotificationHref(value);
      if (safe !== null) {
        assert.equal(new URL(safe, base).origin, new URL(base).origin, JSON.stringify(value));
      }
    }
  }
});
