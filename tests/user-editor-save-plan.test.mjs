import test from "node:test";
import assert from "node:assert/strict";
import { buildUserEditorSavePlan } from "../plugins/directory/frontend/users/user-editor-save-plan.ts";

const baseline = { displayName: "林一舟", enabled: true };
const profileValues = {
  schemaVersion: 7,
  fields: [{ key: "officeExtension" }, { key: "employeeCode" }, { key: "region" }],
  values: { officeExtension: "8206", employeeCode: "E-104", region: "华东" },
};

test("organization-only or role-only save omits profile writes when the active tab has no profile fields", async t => {
  const cases = [
    ["organization assignment", { organizationIds: ["org-existing", "org-ops"] }],
    ["role assignment", { roleIds: ["role-engineer"] }],
  ];
  for (const [name, assignment] of cases) await t.test(name, () => {
    const plan = buildUserEditorSavePlan({
      version: 41,
      baseline,
      formValues: {},
      profileValues,
      canSaveProfile: true,
      ...assignment,
    });

    assert.equal(plan.hasChanges, true);
    assert.equal(plan.profileChanged, false);
    assert.equal(plan.profileValuesChanged, false);
    assert.deepEqual(plan.payload, {
      version: 41,
      ...assignment,
    });
  });
});

test("profile changes are omitted when the editor cannot save profile data", () => {
  const plan = buildUserEditorSavePlan({
    version: 41,
    baseline,
    formValues: { displayName: "Changed name", enabled: false, officeExtension: "8208" },
    profileValues,
    canSaveProfile: false,
    organizationIds: ["org-ops"],
  });

  assert.equal(plan.hasChanges, true);
  assert.equal(plan.profileChanged, false);
  assert.equal(plan.profileValuesChanged, false);
  assert.deepEqual(plan.payload, { version: 41, organizationIds: ["org-ops"] });
});

test("profile edits use the current values while retaining values from unmounted profile fields", () => {
  const plan = buildUserEditorSavePlan({
    version: 42,
    baseline,
    formValues: { displayName: "林一舟", enabled: true, officeExtension: "8208" },
    profileValues,
    canSaveProfile: true,
  });

  assert.equal(plan.profileChanged, false);
  assert.equal(plan.profileValuesChanged, true);
  assert.deepEqual(plan.payload, {
    version: 42,
    profileSchemaVersion: 7,
    profileValues: { officeExtension: "8208", employeeCode: "E-104", region: "华东" },
  });
});

test("combined profile and assignment changes form one request and preserve intentional clears", () => {
  const plan = buildUserEditorSavePlan({
    version: 43,
    baseline,
    formValues: { displayName: "林一舟新版", enabled: false, officeExtension: "", employeeCode: "E-104" },
    profileValues,
    canSaveProfile: true,
    roleIds: ["role-engineer"],
    organizationIds: ["org-ops"],
  });

  assert.equal(plan.hasChanges, true);
  assert.deepEqual(plan.payload, {
    version: 43,
    profile: { displayName: "林一舟新版", enabled: false },
    profileSchemaVersion: 7,
    profileValues: { officeExtension: "", employeeCode: "E-104", region: "华东" },
    roleIds: ["role-engineer"],
    organizationIds: ["org-ops"],
  });
});

test("an explicitly empty display name is not replaced by the saved snapshot", () => {
  const plan = buildUserEditorSavePlan({
    version: 44,
    baseline,
    formValues: { displayName: "", enabled: true },
    profileValues,
    canSaveProfile: true,
  });

  assert.equal(plan.profileChanged, true);
  assert.deepEqual(plan.payload.profile, { displayName: "", enabled: true });
});
