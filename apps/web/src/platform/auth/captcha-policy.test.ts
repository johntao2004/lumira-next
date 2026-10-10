import test from "node:test";
import assert from "node:assert/strict";
import { captchaPolicyModes, captchaRequiresPreflight } from "./captcha-policy.ts";

test("offers distinct off, risk-based, and always-required CAPTCHA policies", () => {
  assert.deepEqual(captchaPolicyModes, ["OFF", "RISK", "ALWAYS"]);
});

test("only an action explicitly listed as always required opens CAPTCHA before submission", () => {
  const alwaysActions = ["PASSWORD_LOGIN"];

  assert.equal(captchaRequiresPreflight("PASSWORD_LOGIN", alwaysActions), true);
  assert.equal(captchaRequiresPreflight("PASSWORD_RECOVERY_REQUEST", alwaysActions), false);
  assert.equal(captchaRequiresPreflight("PASSWORD_LOGIN", []), false);
  assert.equal(captchaRequiresPreflight("PASSWORD_LOGIN", undefined), false);
});
