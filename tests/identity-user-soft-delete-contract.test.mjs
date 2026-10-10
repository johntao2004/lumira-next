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

test("platform_user SQL follows its deleted_at migration contract", () => {
  const migration = readFileSync(path.join(root, "backend/src/main/resources/db/migration/V4__user_soft_delete.sql"), "utf8");
  assert.match(migration, /ALTER TABLE platform_user\s+ADD COLUMN deleted_at\b/u);
  for (const relative of sources) {
    const source = readFileSync(path.join(root, relative), "utf8");
    assert.match(source, /deleted_at IS NULL/iu, relative);
    assert.doesNotMatch(source, /\bNOT\s+(?:[a-z_]\w*\.)?deleted\b/iu, relative);
  }
});
