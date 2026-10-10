import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

test("naming gate accepts conventional paths and rejects frontend, package and migration drift", () => {
  const root = mkdtempSync(path.join(tmpdir(), "lumira-naming-"));
  const write = (name, text = "") => { const file = path.join(root, name); mkdirSync(path.dirname(file), { recursive: true }); writeFileSync(file, text); };
  try {
    mkdirSync(path.join(root, "plugins"));
    write("apps/web/src/features/demo/demo-page.tsx");
    write("backend/src/main/java/com/example/Demo.java", "package com.example;\npublic record Demo(String name) {}");
    write("backend/src/main/resources/db/migration/V2__demo_change.sql");
    const code = "import importlib.util,sys; from pathlib import Path; spec=importlib.util.spec_from_file_location('naming',sys.argv[1]); mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod); mod.ROOT=Path(sys.argv[2]); errors,count=mod.problems(); print('\\n'.join(errors)); sys.exit(bool(errors))";
    const check = () => spawnSync("python3", ["-c", code, path.resolve("scripts/source-naming-smoke.py"), root], { encoding: "utf8" });
    assert.equal(check().status, 0);
    write("apps/web/src/features/demo/BadName.tsx");
    write("backend/src/main/java/com/example/Wrong.java", "package com.other;\npublic class Different {}");
    write("backend/src/main/resources/db/migration/V3__BadChange.sql");
    const rejected = check();
    assert.equal(rejected.status, 1);
    for (const text of ["frontend file name", "Java package/path mismatch", "Java public type/file mismatch", "Flyway file name"]) assert.ok(rejected.stdout.includes(text), text);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test("duplicate gate rejects a newly copied source block without changing the project baseline", () => {
  const root = mkdtempSync(path.join(tmpdir(), "lumira-duplicates-"));
  try {
    const source = readFileSync("apps/web/src/platform/forms/use-submit-action.ts", "utf8");
    writeFileSync(path.join(root, "first.ts"), source);
    const config = JSON.parse(readFileSync(".jscpd.json", "utf8"));
    config.output = path.join(root, "report");
    const configPath = path.join(root, "config.json");
    writeFileSync(configPath, JSON.stringify(config));
    const baseline = path.join(root, "baseline.json");
    const run = flags => spawnSync(process.execPath, [path.resolve("node_modules/jscpd/run-jscpd.js"), root, "--config", configPath, "--baseline", baseline, "--fail-on-empty", "--no-colors", ...flags], { encoding: "utf8" });
    const initial = run(["--update-baseline"]);
    assert.equal(initial.status, 0, initial.stdout + initial.stderr);
    const accepted = readFileSync(baseline, "utf8");
    writeFileSync(path.join(root, "second.ts"), source);
    const rejected = run(["--fail-on-new-clones", "0"]);
    assert.equal(rejected.status, 1, rejected.stdout + rejected.stderr);
    assert.equal(readFileSync(baseline, "utf8"), accepted);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test("locale dictionary duplicate allowance does not hide adjacent source logic", () => {
  const root = mkdtempSync(path.join(tmpdir(), "lumira-locale-duplicates-"));
  try {
    const dictionary = `export const messages = {
  first: "Same locale data",
  second: "Same locale data",
  third: "Same locale data",
  fourth: "Same locale data",
  fifth: "Same locale data",
  sixth: "Same locale data",
  seventh: "Same locale data",
  eighth: "Same locale data",
  ninth: "Same locale data",
  tenth: "Same locale data",
  eleventh: "Same locale data",
  twelfth: "Same locale data",
} as const;
`;
    const first = path.join(root, "first.ts");
    const second = path.join(root, "second.ts");

    const config = JSON.parse(readFileSync(".jscpd.json", "utf8"));
    config.minTokens = 20;
    config.minLines = 8;
    config.output = path.join(root, "report");
    const configPath = path.join(root, "config.json");
    writeFileSync(configPath, JSON.stringify(config));
    const baseline = path.join(root, "baseline.json");
    writeFileSync(baseline, JSON.stringify({ version: 1, fingerprints: {} }));
    const run = (flags) =>
      spawnSync(
        process.execPath,
        [path.resolve("node_modules/jscpd/run-jscpd.js"), root, "--config", configPath, "--baseline", baseline, "--no-colors", ...flags],
        { encoding: "utf8" },
      );

    const sharedLogic = `
export function normalizeLocaleMessage(input: string) {
  const trimmed = input.trim();
  const withoutRepeatedWhitespace = trimmed.replace(/\\s+/gu, " ");
  const normalized = withoutRepeatedWhitespace.normalize("NFC");
  const hasVisibleText = Array.from(normalized).some((character) => character.trim().length > 0);
  const isValid = hasVisibleText && normalized.length <= 2048;
  if (!isValid) {
    return null;
  }
  const result = normalized.slice(0, 2048);
  return result;
}
`;
    writeFileSync(first, `${dictionary}${sharedLogic}`);
    writeFileSync(second, `${dictionary}${sharedLogic}`);
    const duplicatedLogic = run(["--fail-on-new-clones", "0"]);
    assert.equal(duplicatedLogic.status, 1, duplicatedLogic.stdout + duplicatedLogic.stderr);
    assert.match(duplicatedLogic.stdout + duplicatedLogic.stderr, /clone|duplicate/i);
    const report = JSON.parse(
      readFileSync(path.join(root, "report/jscpd-report.json"), "utf8"),
    );
    const dictionaryLineCount = dictionary.split("\n").length - 1;
    assert.ok(report.duplicates.length > 0);
    for (const clone of report.duplicates) {
      assert.ok(clone.firstFile.startLoc.line > dictionaryLineCount);
      assert.ok(clone.secondFile.startLoc.line > dictionaryLineCount);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
