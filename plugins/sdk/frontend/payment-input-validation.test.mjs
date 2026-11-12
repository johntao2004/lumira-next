import assert from "node:assert/strict";
import test from "node:test";
import { validPaymentText } from "./payment-input-validation.ts";
import { trustedPaymentQrUrl } from "./payment-qr-policy.ts";

test("third-channel QR validation is explicit and retains the shared input bounds", () => {
  const order = { providerId: "third-pay", codeUrl: "https://qr.third.example/fixture" };
  const validator = value => new URL(value).origin === "https://qr.third.example";
  assert.equal(trustedPaymentQrUrl(order), null);
  assert.equal(trustedPaymentQrUrl(order, validator), order.codeUrl);
  assert.equal(trustedPaymentQrUrl({ ...order, codeUrl: "https://other.example/fixture" }, validator), null);
  assert.equal(trustedPaymentQrUrl({ ...order, codeUrl: order.codeUrl + "\n" }, () => true), null);
  assert.equal(trustedPaymentQrUrl({ ...order, codeUrl: "a".repeat(513) }, () => true), null);
  assert.equal(trustedPaymentQrUrl(order, () => { throw new Error("bad provider"); }), null);
  assert.equal(trustedPaymentQrUrl({ providerId: "alipay-pay", codeUrl: "https://qr.alipay.com/test" }), "https://qr.alipay.com/test");
  assert.equal(trustedPaymentQrUrl({ providerId: "wechat-pay", codeUrl: "weixin://wxpay/bizpayurl?pr=test" }), "weixin://wxpay/bizpayurl?pr=test");
});

test("payment subjects obey the 127 UTF-8 byte contract for ASCII, Chinese and emoji", () => {
  for (const subject of ["a".repeat(127), "中".repeat(42) + "a", "😀".repeat(31) + "abc"]) {
    assert.equal(validPaymentText(subject, 127), true);
    assert.equal(validPaymentText(subject + "a", 127), false);
  }
});

test("refund reasons obey the same 80 byte contract", () => {
  assert.equal(validPaymentText("中".repeat(26) + "ab", 80), true);
  assert.equal(validPaymentText("中".repeat(27), 80), false);
  assert.equal(validPaymentText("😀".repeat(20), 80), true);
  assert.equal(validPaymentText("😀".repeat(21), 80), false);
});

test("payment text rejects blanks and NUL while preserving multiline reasons", () => {
  for (const value of ["", " \n\t ", "bad\0value"]) {
    assert.equal(validPaymentText(value, 127), false);
  }
  assert.equal(validPaymentText("Reason\nwith\ttwo lines", 80), true);
});
