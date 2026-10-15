import assert from "node:assert/strict";
import test from "node:test";
import { parseSensitiveWordCsv, sensitiveWordValidationIssue, validateSensitivePaste } from "./sensitive-word-csv.ts";

test("sensitive word editor rejects empty and oversized normalized match keys", () => {
  assert.equal(sensitiveWordValidationIssue("\u200b\u200c"), "sensitiveImportCsvEmptyWord");
  assert.equal(sensitiveWordValidationIssue("\u0130".repeat(128)), "sensitiveImportCsvWordTooLong");
  assert.equal(sensitiveWordValidationIssue("a\u200bb"), null);
  assert.equal(sensitiveWordValidationIssue("a".repeat(128)), null);
  assert.equal(sensitiveWordValidationIssue("\u0130".repeat(64)), null);
});

test("CSV and pasted words reject entries the matcher cannot use", () => {
  const words = ["\u200b\u200c", "\u0130".repeat(128)];
  const csv = parseSensitiveWordCsv("word\n" + words.join("\n"));
  const paste = validateSensitivePaste(words.join("\n"));
  assert.equal(csv.words.length, 0);
  for (const result of [csv, paste]) {
    assert.deepEqual(result.issues.map(item => item.key), [
      "sensitiveImportCsvEmptyWord", "sensitiveImportCsvWordTooLong",
    ]);
  }
});

test("FEFF remains part of the original word length before matcher format removal", () => {
  assert.equal(sensitiveWordValidationIssue("\uFEFF" + "a".repeat(127)), null);
  assert.equal(sensitiveWordValidationIssue("\uFEFF" + "a".repeat(128)), "sensitiveImportCsvWordTooLong");
  // The short source expands to 129 units under NFKC; JS trim must not discard FEFF.
  assert.equal(sensitiveWordValidationIssue("\uFEFF" + "\uFB03".repeat(42) + "ab"), "sensitiveImportCsvWordTooLong");
  assert.equal(sensitiveWordValidationIssue("\uFEFF"), "sensitiveImportCsvEmptyWord");
});

test("word boundaries and match whitespace use Java Character.isWhitespace semantics", () => {
  for (const space of ["\u001c", "\u1680", "\u2003", "\u2028", "\u2029", "\u205f", "\u3000", "\u00a0", "\u2007", "\u202f"]) {
    assert.equal(sensitiveWordValidationIssue(space + "a".repeat(128) + space), null);
    assert.equal(sensitiveWordValidationIssue(space), "sensitiveImportCsvEmptyWord");
  }
  assert.equal(sensitiveWordValidationIssue("a\u001cb"), "sensitiveImportCsvControlCharacter");
  assert.equal(sensitiveWordValidationIssue("\u0085word\u0085"), "sensitiveImportCsvControlCharacter");
  assert.equal(sensitiveWordValidationIssue("a\u1680\u1680b"), null);
  assert.equal(parseSensitiveWordCsv("word\n\u001cword\u001c").issues[0].key, "sensitiveImportCsvControlCharacter");
});
