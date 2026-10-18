import test from "node:test";
import assert from "node:assert/strict";
import { languages, loadLocaleMessages, translate } from "../apps/web/src/platform/i18n/messages.ts";
import { localizeProfileFieldLabel } from "../apps/web/src/platform/profile-fields/localization.ts";
import { userFieldLabel, userFieldSourceLabel } from "../apps/web/src/platform/templates/field-labels.ts";

test("transfer and template field names match actual profile labels in every UI language", async () => {
  for (const { value: locale } of languages) {
    await loadLocaleMessages(locale);
    const t = key => translate(locale, key);
    for (const [key, label] of [["realName", "Real name"], ["birthDate", "Birth date"], ["region", "Region"]]) {
      const field = { key, label, source: "profile" };
      assert.equal(userFieldLabel(field, t), localizeProfileFieldLabel(field, t), `${locale}:${key}`);
    }
    assert.equal(userFieldLabel({ key: "enabled", label: "Enabled", source: "account" }, t), t("status"));
    for (const [key, message] of [["roles", "userFieldRoles"], ["organizations", "userFieldOrganizations"]]) {
      const field = { key, label: key, source: key };
      assert.equal(userFieldLabel(field, t), t(message));
      assert.equal(userFieldSourceLabel(key, t), t(message));
    }
  }
  assert.deepEqual(["realName", "birthDate", "region", "roles", "organizations"].map((key, index) =>
    userFieldLabel({ key, label: ["Real name", "Birth date", "Region", "Roles", "Organizations"][index], source: index < 3 ? "profile" : key }, key => translate("zh-CN", key))),
  ["姓名", "出生月份", "地区", "角色", "组织"]);
});

test("configured profile labels and import code semantics remain authoritative", async () => {
  await loadLocaleMessages("zh-CN");
  const t = key => translate("zh-CN", key);
  assert.equal(userFieldLabel({ key: "realName", label: "员工姓名", source: "profile" }, t), "员工姓名");
  assert.equal(userFieldLabel({ key: "region", label: "Region", source: "custom" }, t), "Region");
  assert.equal(userFieldLabel({ key: "roles", label: "Role codes", source: "roles" }, t, "import"), t("userImportRolesCodes"));
  assert.equal(userFieldLabel({ key: "organizations", label: "Organization codes", source: "organizations" }, t, "import"), t("userImportOrganizationsCodes"));
});
