import test from "node:test";
import assert from "node:assert/strict";
import { mergeProfileFieldSettings } from "../plugins/profile-console/frontend/profile-field-submission.ts";

test("a profile-field edit retains unregistered placement and public metadata across hosts", () => {
  const snapshot = {
    schemaVersion: 2,
    fields: [
      {
        key: "qa_skill",
        label: "Skill",
        visible: true,
        placements: [
          { page: "account-profile", tab: "details", sortNo: 20 },
          { page: "admin-user-editor", tab: "profile", sortNo: 10 },
          { page: "public-registration", tab: "additional", sortNo: 30 },
        ],
        publicRegistrationAllowed: true,
      },
      { key: "qa_region", label: "Region", visible: true, placements: [{ page: "account-profile", tab: "custom", sortNo: 10 }], publicRegistrationAllowed: false },
      { key: "qa_internal", label: "Internal", visible: true, placements: [{ page: "admin-user-editor", tab: "profile", sortNo: 20 }], publicRegistrationAllowed: false },
    ],
  };

  const saved = mergeProfileFieldSettings(snapshot, {
    fields: [
      { key: "qa_skill", label: "Updated skill", visible: true },
      { key: "qa_region", label: "Region", visible: true, placements: [], publicRegistrationAllowed: false },
      { key: "qa_internal", label: "Internal", visible: true },
    ],
  });

  assert.equal(saved.schemaVersion, 2);
  assert.deepEqual(saved.fields[0].placements, snapshot.fields[0].placements);
  assert.equal(saved.fields[0].publicRegistrationAllowed, true);
  assert.deepEqual(saved.fields[1].placements, []);
  assert.equal(saved.fields[1].publicRegistrationAllowed, false);
  assert.deepEqual(saved.fields[2].placements, snapshot.fields[2].placements);
  assert.deepEqual(saved.fields.map(field => field.key), ["qa_skill", "qa_region", "qa_internal"]);
});

test("removed fields stay removed while absent field arrays preserve the snapshot", () => {
  const snapshot = { fields: [{ key: "kept" }, { key: "removed" }] };
  assert.deepEqual(mergeProfileFieldSettings(snapshot, { fields: [{ key: "kept", label: "Keep" }] }).fields, [{ key: "kept", label: "Keep" }]);
  assert.deepEqual(mergeProfileFieldSettings(snapshot, {}).fields, snapshot.fields);
});
