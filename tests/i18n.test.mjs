import test from "node:test";
import assert from "node:assert/strict";
import {
  messages,
  languages,
  resolveLocale,
  translate,
} from "../apps/web/src/platform/i18n/messages.ts";
test("all locales have complete translations and matching placeholders", () => {
  assert.equal(languages.length, 8);
  const placeholders = (text) =>
    [...text.matchAll(/\{\w+\}/g)].map((x) => x[0]).sort();
  for (const { value } of languages) {
    assert.deepEqual(
      Object.keys(messages[value]).sort(),
      Object.keys(messages.en).sort(),
    );
    for (const key of Object.keys(messages.en)) {
      assert.ok(messages[value][key].trim(), `${value}:${key}`);
      assert.deepEqual(
        placeholders(messages[value][key]),
        placeholders(messages.en[key]),
        `${value}:${key}`,
      );
    }
  }
});
test("legacy locales and regional tags resolve safely", () => {
  for (const [input, expected] of [
    ["zh", "zh-CN"],
    ["en", "en"],
    ["zh-Hant-HK", "zh-TW"],
    ["zh_TW", "zh-TW"],
    ["fr-CA", "fr"],
    ["de-DE", "de"],
    ["unknown", "zh-CN"],
    [null, "zh-CN"],
  ])
    assert.equal(resolveLocale(input), expected);
});
test("interpolation preserves names and zero counts", () => {
  assert.equal(
    translate("en", "welcome", { name: "Alice" }),
    "Welcome to Alice",
  );
  assert.equal(
    translate("en", "moduleCount", { count: 0 }),
    "0 modules connected",
  );
});
