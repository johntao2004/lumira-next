import test from "node:test";
import assert from "node:assert/strict";
import { buildUserEditorSavePlan, userEditorVersionMismatch, createUserEditorRefreshFence } from "../apps/web/src/features/users/user-editor-save-plan.ts";

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

test("background refetch cannot attach a new optimistic version to an older form snapshot", () => {
  const opened = { ...baseline, version: 41 };
  const draft = { displayName: "Unsaved draft", enabled: true };
  const refreshed = { ...baseline, displayName: "Concurrent edit", version: 42 };
  const oldPlan = buildUserEditorSavePlan({ version: opened.version, baseline: opened, formValues: draft, canSaveProfile: true });
  assert.equal(oldPlan.payload.version, 41);
  assert.equal(userEditorVersionMismatch(opened.version, refreshed.version, [42, 42]), true);
  assert.equal(draft.displayName, "Unsaved draft");
  assert.equal(userEditorVersionMismatch(undefined, refreshed.version, [42]), true);
  assert.equal(userEditorVersionMismatch(refreshed.version, refreshed.version, [42]), false);
  assert.equal(userEditorVersionMismatch(refreshed.version, refreshed.version, [41]), true);
});

test("extended-field drafts stay tied to the hydrated schema and values until explicit refresh", () => {
  const hydrated = { ...profileValues, version: 41 };
  const background = { ...hydrated, version: 42, schemaVersion: 8, values: { ...hydrated.values, officeExtension: "9999" } };
  const draft = { officeExtension: "8207" };
  const plan = buildUserEditorSavePlan({ version: 41, baseline, formValues: draft, profileValues: hydrated, canSaveProfile: true });
  assert.equal(plan.payload.profileSchemaVersion, 7);
  assert.equal(plan.payload.profileValues.officeExtension, "8207");
  assert.equal(userEditorVersionMismatch(41, background.version, [hydrated.version, background.version]), true);
  assert.equal(hydrated.values.officeExtension, "8206");
});

test("late refresh results and cleanup cannot modify the next editor session", () => {
  const fence = createUserEditorRefreshFence();
  const oldSession = fence.begin();
  assert.equal(oldSession(), true);
  fence.invalidate();
  const nextSession = fence.begin();
  let form = "B";
  let refreshing = true;
  if (oldSession()) form = "late A";
  if (oldSession()) refreshing = false;
  assert.equal(form, "B");
  assert.equal(refreshing, true);
  assert.equal(nextSession(), true);
  fence.invalidate();
  assert.equal(nextSession(), false);
});
