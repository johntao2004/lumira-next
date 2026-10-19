import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { builtinDictionaryItemLabel, builtinDictionaryName } from "./builtin-dictionary-labels.ts";
import { loadLocaleMessages, staticLocales, type MessageKey } from "./messages.ts";

const catalog: Partial<Record<MessageKey, string>> = {
  dictWorkOrderFeedbackStatus: "Work order feedback status",
  dictItemClosed: "Closed",
  dictItemQuestion: "Business inquiry",
  dictItemDefaultCategory: "Default category",
};
const t = (key: MessageKey) => catalog[key] ?? key;

test("seed labels translate while administrator-edited and custom labels stay unchanged", () => {
  assert.equal(builtinDictionaryName("work_order_feedback_status", "工单反馈状态", t), "Work order feedback status");
  assert.equal(builtinDictionaryName("work_order_feedback_status", "Custom status", t), "Custom status");
  assert.equal(builtinDictionaryItemLabel("work_order_feedback_status", "CLOSED", "已关闭", t), "Closed");
  assert.equal(builtinDictionaryItemLabel("work_order_feedback_status", "CLOSED", "Closed by ops", t), "Closed by ops");
  assert.equal(builtinDictionaryItemLabel("work_order_feedback_category", "QUESTION", "业务咨询", t), "Business inquiry");
  assert.equal(builtinDictionaryItemLabel("sensitive_word_category", "DEFAULT", "默认分类", t), "Default category");
  assert.equal(builtinDictionaryItemLabel("sensitive_word_category", "CUSTOM", "自定义", t), "自定义");
  assert.equal(builtinDictionaryName("custom", "工单反馈状态", t), "工单反馈状态");
  assert.equal(builtinDictionaryItemLabel("custom", "CLOSED", "已关闭", t), "已关闭");
});

test("every Chinese dictionary seed has a translated presentation in all eight locales", async () => {
  const sql = readFileSync(new URL("../../../../../backend/src/main/resources/db/migration/B52__platform_initialization.sql", import.meta.url), "utf8");
  const dictionaries = new Map<string, string>();
  const names = [...sql.matchAll(/INSERT INTO public\.platform_dictionary VALUES \('([^']+)', '([^']+)', '([^']+)'/g)];
  for (const [, id, code] of names) dictionaries.set(id, code);
  const items = [...sql.matchAll(/INSERT INTO public\.platform_dictionary_item VALUES \('[^']+', '([^']+)', NULL, '([^']+)', '([^']+)'/g)]
    .filter(([, , , label]) => /[\u3400-\u9fff]/u.test(label));
  assert.equal(names.length, 5);
  assert.equal(items.length, 17);
  for (const locale of staticLocales) {
    const messages = await loadLocaleMessages(locale);
    const translate = (key: MessageKey) => messages[key];
    for (const [, , code, label] of names) {
      const translated = builtinDictionaryName(code, label, translate);
      assert.ok(translated, `${locale}: ${code}`);
      if (locale !== "zh-CN") assert.notEqual(translated, label, `${locale}: ${code}`);
    }
    for (const [, id, value, label] of items) {
      const code = dictionaries.get(id)!;
      const translated = builtinDictionaryItemLabel(code, value, label, translate);
      assert.ok(translated, `${locale}: ${code}/${value}`);
      if (locale === "en" || locale === "fr" || locale === "es" || locale === "de" || locale === "ko") {
        assert.doesNotMatch(translated, /[\u3400-\u9fff]/u, `${locale}: ${code}/${value}`);
      }
    }
  }
});
