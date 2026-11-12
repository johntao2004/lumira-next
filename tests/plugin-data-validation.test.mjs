import "../plugins/sdk/frontend/payment-input-validation.test.mjs";
import "../plugins/sensitive-words/frontend/sensitive-word-validation.test.mjs";

import assert from "node:assert/strict";
import test from "node:test";
import { smtpMailboxValid, smtpTextValid } from "../plugins/delivery-settings/frontend/smtp-validation.ts";
import { sensitiveWordValidationIssue } from "../plugins/sensitive-words/frontend/sensitive-word-csv.ts";

test("SMTP display fields preserve the existing UTF-16 contract for Chinese and emoji", () => {
  for (const name of ["中".repeat(120), "😀".repeat(60)]) {
    assert.equal(smtpTextValid(name, 120), true);
    assert.equal(smtpTextValid(name + "a", 120), false);
  }
  for (const username of ["中".repeat(320), "😀".repeat(160)]) {
    assert.equal(smtpTextValid(username, 320), true);
    assert.equal(smtpTextValid(username + "a", 320), false);
  }
  assert.equal(smtpMailboxValid("中文@example.test"), false);
  assert.equal(smtpMailboxValid("😀@example.test"), false);
});

test("sensitive words preserve the 128 UTF-16 unit contract for Chinese and emoji", () => {
  for (const word of ["中".repeat(128), "😀".repeat(64)]) {
    assert.equal(sensitiveWordValidationIssue(word), null);
    assert.equal(sensitiveWordValidationIssue(word + "a"), "sensitiveImportCsvWordTooLong");
  }
});
