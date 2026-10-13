import test from "node:test";
import assert from "node:assert/strict";
import {
  localizeProfileFieldEditorValue,
  localizeProfileFieldGroupLabel,
  localizeProfileFieldLabel,
} from "../apps/web/src/platform/profile-fields/localization.ts";

const translated = key => `translated:${key}`;

test("localizes only unchanged built-in default labels", () => {
  assert.equal(localizeProfileFieldLabel({ key: "displayName", label: "Display name" }, translated), "translated:name");
  assert.equal(localizeProfileFieldLabel({ key: "realName", label: "Real name" }, translated), "translated:realName");
  assert.equal(localizeProfileFieldLabel({ key: "birthDate", label: "Birth date" }, translated), "translated:birthMonth");
  assert.equal(localizeProfileFieldLabel({ key: "region", label: "Region" }, translated), "translated:region");
  assert.equal(localizeProfileFieldLabel({ key: "avatarUrl", label: "Avatar" }, translated), "translated:avatar");
  assert.equal(localizeProfileFieldLabel({ key: "realName", label: "Preferred name" }, translated), "Preferred name");
  assert.equal(localizeProfileFieldLabel({ key: "avatarUrl", label: "Avatar", custom: true }, translated), "Avatar");
});

test("localizes only the built-in basic group", () => {
  assert.equal(localizeProfileFieldGroupLabel({ groupKey: "basic", groupLabel: "Basic information" }, translated), "translated:userBasicInformation");
  assert.equal(localizeProfileFieldGroupLabel({ groupKey: "basic", groupLabel: "Personal details" }, translated), "Personal details");
  assert.equal(localizeProfileFieldGroupLabel({ groupKey: "basic", groupLabel: "Basic information", custom: true }, translated), "Basic information");
});

test("localized editor values leave stored defaults untouched and retain custom input", () => {
  const stored = { key: "displayName", label: "Display name", groupKey: "basic", groupLabel: "Basic information", custom: false };
  assert.equal(localizeProfileFieldEditorValue(stored, "label", stored.label, translated), "translated:name");
  assert.equal(localizeProfileFieldEditorValue(stored, "groupLabel", stored.groupLabel, translated), "translated:userBasicInformation");
  assert.equal(stored.label, "Display name");
  assert.equal(stored.groupLabel, "Basic information");
  assert.equal(localizeProfileFieldEditorValue(stored, "label", "Preferred name", translated), "Preferred name");
});
