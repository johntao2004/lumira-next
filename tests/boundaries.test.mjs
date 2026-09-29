import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
const root = new URL("../", import.meta.url);
function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? files(path.join(dir, entry.name))
      : [path.join(dir, entry.name)],
  );
}
test("new frontend does not import the retired Next runtime or legacy business implementation", () => {
  for (const file of files(new URL("apps/web/src", root).pathname).filter((f) =>
    /\.(ts|tsx)$/.test(f) && !f.includes(`${path.sep}.umi${path.sep}`),
  )) {
    const source = readFileSync(file, "utf8");
    assert.doesNotMatch(
      source,
      /@ant-design\/nextjs-registry|(?:from\s+|import\s*\(|require\()["']next(?:\/[^"']*)?["']|@ant-design\/pro-(?!components\b)|lumira-ui|lumira-backend/,
      file,
    );
  }
});
test("core module package is independent of identity and web adapters", () => {
  for (const file of files(
    new URL("backend/src/main/java/com/lumira/platform/modules", root).pathname,
  )) {
    assert.doesNotMatch(
      readFileSync(file, "utf8"),
      /import com\.lumira\.platform\.(identity|web)/,
      file,
    );
  }
});
