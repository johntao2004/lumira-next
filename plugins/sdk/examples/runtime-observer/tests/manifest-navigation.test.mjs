import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const root = new URL("../", import.meta.url);
const plugin = JSON.parse(readFileSync(new URL("plugin.json", root), "utf8"));
const frontend = readFileSync(new URL("frontend/manifest.ts", root), "utf8");
const backend = readFileSync(new URL("backend/src/main/java/com/lumira/plugins/runtimeobserver/RuntimeObserverModuleConfiguration.java", root), "utf8");
const migration = readFileSync(new URL("migrations/V1__runtime_observer_navigation.sql", root), "utf8");

test("runtime observer owns its page access and read operation", () => {
  assert.equal(plugin.id, "runtime-observer");
  assert.equal(plugin.name, "运行展示屏");
  assert.equal(plugin.version, "1.0.0");
  assert.deepEqual(plugin.dependencies, [{ id: "platform", version: "1.0.0" }]);
  assert.deepEqual(plugin.permissions, [
    "platform:runtime-observer:access",
    "platform:runtime-observer:read",
  ]);

  const route = plugin.routes.find(candidate => candidate.id === "runtime-observer");
  assert.ok(route);
  assert.match(route.label, /^[a-z][a-zA-Z0-9._:-]{0,127}$/);
  assert.equal(route.label, "runtimeObserverTitle");
  assert.equal(route.permission, "platform:runtime-observer:access");
  assert.deepEqual(route.operations, { read: "platform:runtime-observer:read" });
  assert.equal(route.group, "operations");
  assert.equal(route.order, 951);
  assert.equal(route.global, true);

  const frontendDescriptor = frontend.match(/export const runtimeObserverManifest = \{\s*id:\s*"([^"]+)",\s*version:\s*"([^"]+)"/);
  const frontendRoute = frontend.match(/routes:\s*\[\s*\{\s*id:\s*"runtime-observer",\s*label:\s*"([^"]+)"/);
  const backendDescriptor = backend.match(/new PlatformModule\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*List\.of\("([^"]+)"\)\)/);
  assert.ok(frontendDescriptor);
  assert.ok(frontendRoute);
  assert.ok(backendDescriptor);
  assert.equal(frontendDescriptor[1], plugin.id);
  assert.equal(frontendDescriptor[2], plugin.version);
  assert.equal(frontendRoute[1], route.label);
  assert.equal(backendDescriptor[1], plugin.id);
  assert.equal(backendDescriptor[2], plugin.name);
  assert.equal(backendDescriptor[3], plugin.version);
  assert.equal(backendDescriptor[4], plugin.dependencies[0].id);
});

test("navigation migration has a distinct UUID and refuses foreign ownership", () => {
  assert.match(migration, /00000000-0000-4000-8000-000000000951/);
  assert.match(migration, /'runtimeObserverTitle'\s*,\s*'运行展示屏'/);
  assert.match(migration, /WHERE platform_navigation_menu\.module_id = 'runtime-observer'/);
  assert.match(migration, /COALESCE\(module_id, ''\) <> 'runtime-observer'/);
});
