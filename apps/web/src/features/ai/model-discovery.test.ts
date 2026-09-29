import test from "node:test";
import assert from "node:assert/strict";
import { discoveryErrorKey, modelSelectionIsValid, selectDiscoveredModel } from "./model-discovery.ts";
import type { ModelOption } from "./types.ts";

const option = (id: string): ModelOption => ({ id, name: id, inputModalities: [], outputModalities: [] });

test("selects the only real result and preserves a currently selected returned ID", () => {
  assert.equal(selectDiscoveredModel([option("deepseek-flash")]), "deepseek-flash");
  assert.equal(selectDiscoveredModel([option("a"), option("b")], "b"), "b");
});

test("does not invent a model ID for empty or ambiguous results", () => {
  assert.equal(selectDiscoveredModel([]), undefined);
  assert.equal(selectDiscoveredModel([option("a"), option("b")]), undefined);
  assert.equal(selectDiscoveredModel([option("a")], "stale"), "a");
});

test("allows an unchanged saved model after reopening, but requires fresh discovery after connection changes", () => {
  const saved = option("deepseek-flash").id;
  assert.equal(modelSelectionIsValid(saved, saved, { models: [], status: "idle", invalidated: false }), true);
  assert.equal(modelSelectionIsValid(saved, saved, { models: [], status: "error", invalidated: false }), true);
  assert.equal(modelSelectionIsValid(saved, saved, { models: [], status: "idle", invalidated: true }), false);
  assert.equal(modelSelectionIsValid(saved, saved, { models: [], status: "empty", invalidated: false }), false);
  assert.equal(modelSelectionIsValid(saved, saved, { models: [option("other")], status: "success", invalidated: false }), false);
  assert.equal(modelSelectionIsValid(saved, "other", { models: [option("other")], status: "success", invalidated: false }), true);
});

test("maps safe provider discovery codes to distinct localized messages", () => {
  assert.equal(discoveryErrorKey({ message: "aiProviderAuthenticationFailed" }), "aiProviderAuthenticationFailed");
  assert.equal(discoveryErrorKey({ message: "aiModelDiscoveryEndpointUnavailable" }), "aiModelDiscoveryEndpointUnavailable");
  assert.equal(discoveryErrorKey({ message: "aiProviderRateLimited" }), "aiProviderRateLimited");
  assert.equal(discoveryErrorKey({ status: 504 }), "aiProviderRequestTimedOut");
  assert.equal(discoveryErrorKey({ message: "upstream body containing secret must be ignored" }), "aiModelDiscoveryFailed");
});
