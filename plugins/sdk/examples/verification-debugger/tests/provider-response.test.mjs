import assert from "node:assert/strict";
import test from "node:test";
import { isProviderSendSuccessful } from "../frontend/provider-response.ts";

test("Aliyun requires an HTTP success and the provider success code", () => {
  assert.equal(isProviderSendSuccessful("aliyun", 200, { Code: "OK", Message: "OK" }), true);
  assert.equal(isProviderSendSuccessful("aliyun", 200, { Code: "isv.MOBILE_NUMBER_ILLEGAL", Message: "Invalid phone" }), false);
  assert.equal(isProviderSendSuccessful("aliyun", 429, { Code: "OK" }), false);
  assert.equal(isProviderSendSuccessful("aliyun", 200, { unexpected: "shape" }), false);
});

test("Tencent requires a non-empty all-success status set and no provider error", () => {
  assert.equal(isProviderSendSuccessful("tencent", 200, {
    Response: { SendStatusSet: [{ Code: "Ok" }, { Code: "Ok" }], RequestId: "req-ok" },
  }), true);
  assert.equal(isProviderSendSuccessful("tencent", 200, {
    Response: { Error: { Code: "AuthFailure", Message: "Denied" }, SendStatusSet: [{ Code: "Ok" }] },
  }), false);
  assert.equal(isProviderSendSuccessful("tencent", 200, {
    Response: { SendStatusSet: [{ Code: "Ok" }, { Code: "LimitExceeded" }] },
  }), false);
  assert.equal(isProviderSendSuccessful("tencent", 200, { Response: { SendStatusSet: [] } }), false);
  assert.equal(isProviderSendSuccessful("tencent", 429, {
    Response: { SendStatusSet: [{ Code: "Ok" }] },
  }), false);
  assert.equal(isProviderSendSuccessful("tencent", 200, { unexpected: "shape" }), false);
});
