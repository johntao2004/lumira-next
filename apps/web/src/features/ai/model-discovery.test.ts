import test from "node:test";
import assert from "node:assert/strict";
import { discoveryErrorKey, modelNamesAfterManualEdit, modelNamesForSave, modelSelectionIsValid, selectDiscoveredModel, selectDiscoveredModels } from "./model-discovery.ts";
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

test("preserves ordered selections and falls back to the first discovery result", () => {
  const models = [option("a"), option("b"), option("c")];
  assert.deepEqual(selectDiscoveredModels(models, ["c", "a", "missing", "c"]), ["c", "a"]);
  assert.deepEqual(selectDiscoveredModels(models, ["missing"]), ["a"]);
  assert.deepEqual(selectDiscoveredModels([], ["saved"]), []);
});

test("manual editors keep configured fallbacks when changing the default model", () => {
  assert.deepEqual(modelNamesAfterManualEdit(["default", "fallback"], "default"), ["default", "fallback"]);
  assert.deepEqual(modelNamesAfterManualEdit(["default", "fallback"], "  replacement  "), ["replacement", "fallback"]);
  assert.deepEqual(modelNamesAfterManualEdit(["default", "fallback"], "fallback"), ["fallback", "default"]);
  assert.deepEqual(modelNamesAfterManualEdit(["default", "fallback", "fallback"], "default"), ["default", "fallback"]);
  assert.deepEqual(modelNamesAfterManualEdit([], "new-model"), ["new-model"]);
  assert.deepEqual(modelNamesAfterManualEdit(["default", "fallback"], " "), []);
});

test("save uses the visible manual default when discovery controls are unavailable", () => {
  assert.deepEqual(modelNamesForSave(false, ["default", "fallback"], [], "replacement"), ["replacement", "fallback"]);
  assert.deepEqual(modelNamesForSave(false, ["default", "fallback"], ["stale"], "fallback"), ["fallback", "default"]);
  assert.deepEqual(modelNamesForSave(true, ["saved"], ["discovered", "fallback"], "ignored"), ["discovered", "fallback"]);
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
