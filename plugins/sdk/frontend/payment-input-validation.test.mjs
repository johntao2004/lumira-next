import assert from "node:assert/strict";
import test from "node:test";
import { validPaymentText } from "./payment-input-validation.ts";

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
