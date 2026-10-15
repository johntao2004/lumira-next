import assert from "node:assert/strict";
import test from "node:test";
import {
  beginAuthenticationMethodEnable,
  finishAuthenticationMethodConfiguration,
  readyEnabledMethods,
  reconcileAuthenticationMethodDraft,
  type AuthenticationMethodDraft,
} from "./authentication-method-flow.ts";
import type { PrimaryMethod } from "@/platform/auth/api-types";

const ready: PrimaryMethod[] = ["PASSWORD"];
const initial: AuthenticationMethodDraft = { enabledMethods: ["PASSWORD"], configuration: null };

function begin(method: PrimaryMethod, overrides: Partial<Parameters<typeof beginAuthenticationMethodEnable>[1]> = {}) {
  return beginAuthenticationMethodEnable(initial, {
    id: 1,
    method,
    availableMethods: ready,
    configurationRoute: "smtp",
    canAccessConfiguration: true,
    canUpdateConfiguration: true,
    ...overrides,
  });
}

test("unready method is selected only in the temporary configuration draft and cancellation restores prior methods", () => {
  const started = begin("EMAIL_OTP", { availableMethods: ready });
  assert.equal(started.result, "configure");
  assert.deepEqual(started.draft.enabledMethods, ["PASSWORD", "EMAIL_OTP"]);

  const cancelled = finishAuthenticationMethodConfiguration(started.draft, 1, false);
  assert.equal(cancelled.result, "unavailable");
  assert.deepEqual(cancelled.draft.enabledMethods, ["PASSWORD"]);
  assert.equal(cancelled.draft.configuration, null);
});

test("a saved provider is kept only when the fresh server readiness list contains it", () => {
  const started = begin("PASSKEY", { configurationRoute: "passkey" });
  assert.equal(started.result, "configure");

  const readyResult = finishAuthenticationMethodConfiguration(started.draft, 1, true);
  assert.equal(readyResult.result, "ready");
  assert.deepEqual(readyResult.draft.enabledMethods, ["PASSWORD", "PASSKEY"]);

  const incomplete = finishAuthenticationMethodConfiguration(started.draft, 1, false);
  assert.equal(incomplete.result, "unavailable");
  assert.deepEqual(incomplete.draft.enabledMethods, ["PASSWORD"]);
});

test("already-ready methods enable directly and existing unavailable methods are hidden from the initial draft", () => {
  assert.equal(begin("PASSWORD").result, "enabled");
  assert.deepEqual(readyEnabledMethods(["PASSWORD", "SMS_OTP"], ready), ["PASSWORD"]);
});

test("a second start is idempotently rejected and stale completions cannot change a canceled draft", () => {
  const started = begin("SMS_OTP", { id: 9, configurationRoute: "sms" });
  const repeated = beginAuthenticationMethodEnable(started.draft, {
    id: 10,
    method: "WECHAT",
    availableMethods: ready,
    configurationRoute: "wechat",
    canAccessConfiguration: true,
    canUpdateConfiguration: true,
  });
  assert.equal(repeated.result, "busy");
  assert.equal(repeated.draft.configuration?.id, 9);
  assert.equal(finishAuthenticationMethodConfiguration(repeated.draft, 10, true).result, "ignored");

  const cancelled = finishAuthenticationMethodConfiguration(repeated.draft, 9, false);
  assert.deepEqual(cancelled.draft.enabledMethods, ["PASSWORD"]);
  assert.equal(finishAuthenticationMethodConfiguration(cancelled.draft, 9, true).result, "ignored");
});

test("missing route access or update permission opens a blocked transaction without authorizing the provider view", () => {
  const noAccess = begin("EMAIL_OTP", { canAccessConfiguration: false });
  assert.equal(noAccess.result, "blocked");
  assert.equal(noAccess.draft.configuration?.blockReason, "access");
  assert.deepEqual(finishAuthenticationMethodConfiguration(noAccess.draft, 1, false).draft.enabledMethods, ["PASSWORD"]);

  const readOnly = begin("WECHAT", { configurationRoute: "wechat", canUpdateConfiguration: false });
  assert.equal(readOnly.result, "blocked");
  assert.equal(readOnly.draft.configuration?.blockReason, "update");
  assert.deepEqual(finishAuthenticationMethodConfiguration(readOnly.draft, 1, false).draft.enabledMethods, ["PASSWORD"]);

  const noConfiguration = begin("PASSKEY", { configurationRoute: null });
  assert.equal(noConfiguration.draft.configuration?.blockReason, "configuration");
});

test("readiness refresh preserves unrelated drafts and keeps a method only while its own setup is active", () => {
  const source: AuthenticationMethodDraft = { enabledMethods: ["PASSWORD", "SMS_OTP"], configuration: null };
  const started = beginAuthenticationMethodEnable(source, {
    id: 4,
    method: "EMAIL_OTP",
    availableMethods: ["PASSWORD", "SMS_OTP"],
    configurationRoute: "smtp",
    canAccessConfiguration: true,
    canUpdateConfiguration: true,
  });
  const reconciled = reconcileAuthenticationMethodDraft(started.draft, ready);
  assert.deepEqual(reconciled.enabledMethods, ["PASSWORD", "EMAIL_OTP"]);
  const finished = finishAuthenticationMethodConfiguration(reconciled, 4, false);
  const afterClose = reconcileAuthenticationMethodDraft(finished.draft, ready);
  assert.deepEqual(afterClose.enabledMethods, ["PASSWORD"]);
});
