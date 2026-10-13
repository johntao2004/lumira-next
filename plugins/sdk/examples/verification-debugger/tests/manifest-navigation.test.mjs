import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const root = new URL("../", import.meta.url);
const plugin = JSON.parse(readFileSync(new URL("plugin.json", root), "utf8"));
const frontend = readFileSync(new URL("frontend/manifest.ts", root), "utf8");
const backend = readFileSync(new URL("backend/src/main/java/com/lumira/plugins/verificationdebugger/VerificationDebuggerModuleConfiguration.java", root), "utf8");
const route = plugin.routes.find(candidate => candidate.id === "verification-debugger");

test("verification debugger manifests keep a database-safe route label and synchronized module metadata", () => {
  assert.ok(route, "plugin.json declares its verification-debugger route");
  assert.match(route.label, /^[a-z][a-zA-Z0-9._:-]{0,127}$/);
  const frontendDescriptor = frontend.match(/export const verificationDebuggerManifest = \{\s*id:\s*"([^"]+)",\s*version:\s*"([^"]+)"/);
  const frontendRoute = frontend.match(/routes:\s*\[\s*\{\s*id:\s*"verification-debugger",\s*label:\s*"([^"]+)"/);
  const backendDescriptor = backend.match(/new PlatformModule\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*List\.of\("([^"]+)"\)\)/);
  assert.ok(frontendDescriptor, "frontend manifest declares module id and version");
  assert.ok(frontendRoute, "frontend manifest declares the verification-debugger navigation route");
  assert.ok(backendDescriptor, "backend module configuration declares id, name, version, and dependency");
  assert.equal(frontendRoute[1], route.label);
  assert.equal(frontendDescriptor[1], plugin.id);
  assert.equal(frontendDescriptor[2], plugin.version);
  assert.equal(backendDescriptor[1], plugin.id);
  assert.equal(backendDescriptor[2], plugin.name);
  assert.equal(backendDescriptor[3], plugin.version);
  assert.equal(backendDescriptor[4], plugin.dependencies[0].id);
});
