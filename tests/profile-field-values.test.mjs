import test from "node:test";
import assert from "node:assert/strict";
import { preserveProfileFieldValues } from "../apps/web/src/platform/profile-fields/values.ts";

test("saving an active profile tab retains values from unmounted tabs", () => {
  const fields = ["qa_skill", "qa_region", "qa_retained"].map(key => ({ key }));
  const saved = {
    qa_skill: "old skill",
    qa_region: "north",
    qa_retained: "hidden tab value",
  };

  assert.deepEqual(
    preserveProfileFieldValues({ qa_skill: "new skill" }, fields, saved),
    {
      qa_skill: "new skill",
      qa_region: "north",
      qa_retained: "hidden tab value",
    },
  );
});

test("an explicit empty value still clears that field", () => {
  assert.deepEqual(
    preserveProfileFieldValues(
      { qa_skill: "" },
      [{ key: "qa_skill" }],
      { qa_skill: "old skill" },
    ),
    { qa_skill: "" },
  );
});

test("an explicit null or undefined from a cleared control does not restore the old value", () => {
  const fields = [{ key: "clearedSelect" }, { key: "clearedNumber" }];
  const saved = { clearedSelect: "old option", clearedNumber: "42" };

  assert.deepEqual(
    preserveProfileFieldValues({ clearedSelect: undefined, clearedNumber: null }, fields, saved),
    { clearedSelect: "", clearedNumber: "" },
  );
});
