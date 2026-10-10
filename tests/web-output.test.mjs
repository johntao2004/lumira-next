import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { assertNoWebSourceMapExposure } from "../scripts/verify-web-output.mjs";

test("production output check accepts clean assets and rejects map files and references", () => {
  const directory = mkdtempSync(path.join(os.tmpdir(), "lumira-web-output-"));
  try {
    writeFileSync(path.join(directory, "index.html"), "<html></html>");
    mkdirSync(path.join(directory, "assets"));
    writeFileSync(
      path.join(directory, "assets/app.js"),
      "export const ready = true;",
    );
    assertNoWebSourceMapExposure(directory);

    writeFileSync(
      path.join(directory, "assets/app.js"),
      "//# sourceMappingURL=app.js.map",
    );
    assert.throws(
      () => assertNoWebSourceMapExposure(directory),
      /sourceMappingURL reference/,
    );
    writeFileSync(
      path.join(directory, "assets/app.js.map"),
      '{"sourcesContent":["source"]}',
    );
    assert.throws(
      () => assertNoWebSourceMapExposure(directory),
      /source map file/,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
