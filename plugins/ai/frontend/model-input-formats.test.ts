import assert from "node:assert/strict";
import test from "node:test";
import { acceptsFile, defaultFileFormats, modelFileFormats } from "./model-input-formats.ts";

test("DeepSeek Flash defaults to images while unverified models remain text only", () => {
  assert.ok(defaultFileFormats("DEEPSEEK", "deepseek-flash").includes("png"));
  assert.ok(!defaultFileFormats("DEEPSEEK", "deepseek-reasoner").includes("png"));
  assert.deepEqual(defaultFileFormats("TYPESAFE_JEV", "jev-latest"), []);
});

test("explicit model configuration takes precedence, including disabling all attachments", () => {
  const model = { id: "deepseek-flash", name: "Flash", inputModalities: ["image"], outputModalities: ["text"], supportedFileFormats: [] };
  assert.deepEqual(modelFileFormats(model), []);
  assert.equal(acceptsFile(modelFileFormats(model), "image.png"), false);
  assert.deepEqual(modelFileFormats({ ...model, supportedFileFormats: ["pdf"] }), ["pdf"]);
});

test("picker and pasted attachments use the selected model's exact extensions", () => {
  assert.equal(acceptsFile(["png", "pdf"], "image.PNG"), true);
  assert.equal(acceptsFile(["png", "pdf"], "report.pdf.exe"), false);
  assert.equal(acceptsFile(["pdf"], "image.png"), false);
  assert.deepEqual(modelFileFormats(), []);
});
