import test from "node:test";
import assert from "node:assert/strict";
import { profileFieldValueValid } from "../apps/web/src/platform/profile-fields/validation.ts";
import { smtpHostValid, smtpMailboxValid, smtpTextValid } from "../plugins/delivery-settings/frontend/smtp-validation.ts";

const field = (type, extra = {}) => ({ key: "sample", label: "Sample", type, required: false, ...extra });

test("profile required checks preserve zero and optional clearing across the server's whitespace rules", () => {
  for (const value of [undefined, null, "", " \t\r\n", "\u3000\u2003"]) {
    assert.equal(profileFieldValueValid(field("TEXT"), value), true);
    assert.equal(profileFieldValueValid(field("TEXT", { required: true }), value), false);
  }
  assert.equal(profileFieldValueValid(field("NUMBER", { required: true }), 0), true);
  assert.equal(profileFieldValueValid(field("TEXT", { required: true }), "\u00a0"), true);
  for (const value of [false, {}, [], "\0", "before\0after"]) assert.equal(profileFieldValueValid(field("TEXT"), value), false);
});

test("profile decimal checks retain exact strings and reject unsupported numeric formats", () => {
  for (const value of ["0", "-0", "-12.5", " 12.50 ", "123456789012345678901234567890", "12345678901234567890.1234567890", "0.0000000001", "0.0000000000"]) {
    assert.equal(profileFieldValueValid(field("NUMBER"), value), true, value);
  }
  for (const value of ["01", "-01", "+1", "1e3", ".5", "1.", "1,000", "１２", "1234567890123456789012345678901", "123456789012345678901.1234567890", "0.00000000001", NaN, Infinity, -Infinity]) {
    assert.equal(profileFieldValueValid(field("NUMBER"), value), false, String(value));
  }
});

test("profile dates use strict ISO calendar dates including leap centuries", () => {
  for (const value of ["2024-02-29", "2000-02-29", "2026-10-15", "0000-02-29", "9999-12-31"]) {
    assert.equal(profileFieldValueValid(field("DATE"), value), true, value);
  }
  for (const value of ["2023-02-29", "1900-02-29", "2026-04-31", "2026-00-01", "2026-13-01", "2026-01-00", "2026-2-01", "2026-01-1", "2026-01-01T00:00:00Z", "10000-01-01"]) {
    assert.equal(profileFieldValueValid(field("DATE"), value), false, value);
  }
});

test("profile text and selection constraints mirror current schema values", () => {
  assert.equal(profileFieldValueValid(field("TEXT"), "a".repeat(200)), true);
  assert.equal(profileFieldValueValid(field("TEXT"), "a".repeat(201)), false);
  assert.equal(profileFieldValueValid(field("TEXT"), "😀".repeat(100)), true);
  assert.equal(profileFieldValueValid(field("TEXT"), "😀".repeat(101)), false);
  assert.equal(profileFieldValueValid(field("TEXTAREA"), "a".repeat(2000)), true);
  assert.equal(profileFieldValueValid(field("TEXTAREA"), "a".repeat(2001)), false);
  const select = field("SELECT", { options: ["first", { value: "second", label: "Second" }] });
  assert.equal(profileFieldValueValid(select, "first"), true);
  assert.equal(profileFieldValueValid(select, "second"), true);
  assert.equal(profileFieldValueValid(select, "Second"), false);
  const dictionary = field("SELECT", { dictionaryCode: "regions" });
  assert.equal(profileFieldValueValid(dictionary, "ABCDEF00-1234-5678-9012-abcdef123456"), true);
  assert.equal(profileFieldValueValid(dictionary, "1-1-1-1-1"), false);
});

test("SMTP host and mailbox checks allow server-supported single-label addresses", () => {
  for (const host of ["mail", "smtp.example.com", "127.0.0.1", "a".repeat(63)]) assert.equal(smtpHostValid(host), true, host);
  for (const host of ["", "https://smtp.example.com", "mail..example.com", ".mail", "mail.", "-mail", "mail-", "a".repeat(64), `${"a".repeat(63)}.${"a".repeat(63)}.${"a".repeat(63)}.${"a".repeat(63)}`]) {
    assert.equal(smtpHostValid(host), false, host);
  }
  for (const address of ["sender@mail", "sender+tag@example.com", "a.b@example.com", "a".repeat(64) + "@example.com"]) assert.equal(smtpMailboxValid(address), true, address);
  for (const address of ["sender", "@example.com", "a@@example.com", ".a@example.com", "a.@example.com", "a..b@example.com", "a b@example.com", "a@example.com\r\nBcc:x@example.com", "a".repeat(65) + "@example.com"]) {
    assert.equal(smtpMailboxValid(address), false, address);
  }
});

test("SMTP text rejects control characters and overlong saved values", () => {
  assert.equal(smtpTextValid("", 120), true);
  assert.equal(smtpTextValid("Sender".repeat(20), 120), true);
  for (const value of ["a".repeat(121), "sender\r\nBcc: injected", "sender\u0085", null, 123]) assert.equal(smtpTextValid(value, 120), false);
});
