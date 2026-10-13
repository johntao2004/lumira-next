import assert from "node:assert/strict";
import test from "node:test";
import {
  appendSample,
  buildDependencyGraph,
  formatMetric,
  mergeModuleRecords,
  normalizeStatus,
} from "../frontend/model.mjs";

test("missing values stay unknown instead of becoming zero or a ready state", () => {
  assert.equal(formatMetric(null), "—");
  assert.equal(formatMetric(undefined), "—");
  assert.equal(formatMetric(Number.NaN), "—");
  assert.equal(normalizeStatus(null), "UNKNOWN");
  assert.equal(normalizeStatus("  "), "UNKNOWN");
  assert.equal(normalizeStatus("running"), "RUNNING");
});

test("module and plugin dependencies merge without duplicate nodes or edges", () => {
  const records = mergeModuleRecords(
    [
      { id: "payments", name: "Payments", version: "1.0.0", enabled: true, dependencies: ["platform", "shared"] },
      { id: "shared", name: "Shared", version: "1.0.0", enabled: true, dependencies: ["platform"] },
    ],
    [
      {
        id: "payments",
        name: "Payments plugin",
        version: "1.0.0",
        installed: true,
        enabled: true,
        status: "RUNNING",
        manifest: { dependencies: [{ id: "platform" }, { id: "shared" }, { id: "shared" }] },
      },
    ],
  );
  const payments = records.find(record => record.id === "payments");
  assert.ok(payments);
  assert.deepEqual(payments.dependencies, ["platform", "shared"]);
  assert.equal(records.filter(record => record.id === "payments").length, 1);
  assert.equal(payments.status, "RUNNING");
  assert.equal(payments.installed, true);

  const graph = buildDependencyGraph(records);
  assert.equal(graph.nodes.filter(node => node.id === "payments").length, 1);
  assert.equal(graph.edges.filter(edge => edge.id === "shared->payments").length, 1);
  assert.equal(graph.edges.find(edge => edge.id === "shared->payments")?.from, "shared");
  assert.equal(graph.edges.find(edge => edge.id === "shared->payments")?.to, "payments");
});

test("dependencies missing from both host lists remain unknown graph nodes", () => {
  const records = mergeModuleRecords([
    { id: "consumer", name: "Consumer", version: "1.0.0", enabled: true, dependencies: ["ghost-module"] },
  ]);
  const graph = buildDependencyGraph(records);
  const missing = graph.nodes.find(node => node.id === "ghost-module");
  assert.equal(missing?.known, false);
  assert.equal(missing?.label, "未知模块：ghost-module");
  assert.equal(missing?.record, null);
  assert.equal(graph.edges.some(edge => edge.id === "ghost-module->consumer"), true);
});

test("module enablement does not imply a running plugin artifact", () => {
  const [record] = mergeModuleRecords([
    { id: "enabled-module", name: "Enabled module", enabled: true, dependencies: [] },
  ]);
  assert.equal(record.enabled, true);
  assert.equal(record.status, null);
  assert.equal(record.installed, null);
  assert.equal(record.plugin, null);
});

test("samples ignore unknown measurements, deduplicate measuredAt, and keep a bounded window", () => {
  const empty = [];
  const unknown = appendSample(empty, {
    summary: { measuredAt: "2026-10-13T00:00:00Z", pendingOutbox: null, failedOutbox: undefined },
    machine: { processCpuUsage: null },
  });
  assert.equal(unknown, empty);

  const first = {
    summary: {
      measuredAt: "2026-10-13T00:00:00Z",
      pendingOutbox: 4,
      processingOutbox: 1,
      failedOutbox: 0,
      deadLetterOutbox: 0,
    },
    machine: { processCpuUsage: 0.25 },
  };
  const sample = appendSample(empty, first);
  assert.equal(sample.length, 1);
  assert.equal(appendSample(sample, first), sample);
  assert.equal(sample[0].pendingOutbox, 4);
  assert.equal(sample[0].processCpuUsage, 0.25);

  let bounded = [];
  for (let index = 0; index < 35; index += 1) {
    bounded = appendSample(bounded, {
      summary: {
        measuredAt: "2026-10-13T00:00:" + String(index).padStart(2, "0") + "Z",
        pendingOutbox: index,
      },
    });
  }
  assert.equal(bounded.length, 30);
  assert.equal(bounded[0].pendingOutbox, 5);
  assert.equal(bounded.at(-1).pendingOutbox, 34);
});
