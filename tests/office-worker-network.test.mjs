import assert from "node:assert/strict";
import test from "node:test";
import { legacyUnlabeledNetworkCanBeRetired } from "../scripts/office-worker-runtime.mjs";

const expected = {
  expectedName: "lumira-office-abc-local-private",
  expectedProject: "lumira-office-abc-local",
  expectedNetworkKey: "office-worker",
  allowedEndpointIds: ["worker"],
};

test("an unlabeled legacy office network with only our endpoints can be replaced", () => {
  assert.equal(
    legacyUnlabeledNetworkCanBeRetired({
      ...expected,
      name: expected.expectedName,
      ownerLabel: "",
      profileLabel: "",
      projectLabel: "",
      networkKey: "",
      endpointIds: ["worker"],
    }),
    true,
  );
  assert.equal(
    legacyUnlabeledNetworkCanBeRetired({
      ...expected,
      name: expected.expectedName,
      ownerLabel: "",
      profileLabel: "",
      projectLabel: expected.expectedProject,
      networkKey: expected.expectedNetworkKey,
      endpointIds: [],
    }),
    true,
  );
});

test("a labeled, foreign, or occupied office network is left alone", () => {
  assert.equal(
    legacyUnlabeledNetworkCanBeRetired({
      ...expected,
      name: expected.expectedName,
      ownerLabel: "someone-else",
      profileLabel: "",
      projectLabel: "",
      networkKey: "",
      endpointIds: [],
    }),
    false,
  );
  assert.equal(
    legacyUnlabeledNetworkCanBeRetired({
      ...expected,
      name: expected.expectedName,
      ownerLabel: "",
      profileLabel: "",
      projectLabel: "other-project",
      networkKey: "",
      endpointIds: [],
    }),
    false,
  );
  assert.equal(
    legacyUnlabeledNetworkCanBeRetired({
      ...expected,
      name: expected.expectedName,
      ownerLabel: "",
      profileLabel: "",
      projectLabel: "",
      networkKey: "other-network",
      endpointIds: [],
    }),
    false,
  );
  assert.equal(
    legacyUnlabeledNetworkCanBeRetired({
      ...expected,
      name: expected.expectedName,
      ownerLabel: "",
      profileLabel: "",
      projectLabel: "",
      networkKey: "",
      endpointIds: ["foreign"],
    }),
    false,
  );
});
