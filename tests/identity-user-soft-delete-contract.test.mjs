import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sources = [
  "backend/src/main/java/com/lumira/platform/identity/AccountLocaleRepository.java",
  "backend/src/main/java/com/lumira/platform/identity/AccountSecurityService.java",
  "backend/src/main/java/com/lumira/platform/identity/SecondFactorLoginService.java",
  "plugins/mfa/backend/src/main/java/com/lumira/platform/mfa/MfaStore.java",
];

test("platform_user soft deletion is present in the initialization baseline", () => {
  const baseline = readFileSync(path.join(root, "backend/src/main/resources/db/migration/B52__platform_initialization.sql"), "utf8");
  const userTable = baseline.match(/CREATE TABLE public\.platform_user \([\s\S]*?^\);/mu)?.[0];
  assert.ok(userTable, "B52 must define platform_user");
  assert.match(userTable, /^\s+deleted_at timestamp with time zone,/mu);
  assert.match(baseline, /CREATE INDEX platform_user_deleted_at_idx ON public\.platform_user USING btree \(deleted_at\);/u);
  for (const relative of sources) {
    const source = readFileSync(path.join(root, relative), "utf8");
    assert.match(source, /deleted_at IS NULL/iu, relative);
    assert.doesNotMatch(source, /\bNOT\s+(?:[a-z_]\w*\.)?deleted\b/iu, relative);
  }
});
