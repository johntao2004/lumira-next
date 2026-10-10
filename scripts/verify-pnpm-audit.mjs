import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  verifyActiveMakoDependencyResolution,
  verifyBracesMitigation,
  verifyDependencyPatchIntegrity,
  verifyRouterMitigation,
  verifySprintfMitigation,
} from "./dependency-patch-checks.mjs";

const expectedAdvisories = new Map([
  [
    "https://github.com/advisories/GHSA-848j-6mx2-7j84",
    {
      packageName: "elliptic",
      severity: "low",
      version: "6.6.1",
      paths: [
        "apps__web>@umijs/max>umi>@umijs/bundler-webpack>node-libs-browser>crypto-browserify>browserify-sign>elliptic",
      ],
    },
  ],
  [
    "https://github.com/advisories/GHSA-wrjc-x8rr-h8h6",
    {
      packageName: "react-router",
      severity: "moderate",
      version: "6.30.6",
      paths: [
        "apps__web>@umijs/max>umi>@umijs/preset-umi>react-router",
        "apps__web>@umijs/max>umi>@umijs/renderer-react>react-router-dom>react-router",
      ],
    },
  ],
  [
    "https://github.com/advisories/GHSA-337j-9hxr-rhxg",
    {
      packageName: "react-router",
      severity: "moderate",
      version: "6.30.6",
      paths: [
        "apps__web>@umijs/max>umi>@umijs/preset-umi>react-router",
        "apps__web>@umijs/max>umi>@umijs/renderer-react>react-router-dom>react-router",
      ],
      // This upstream fix starts at React Router 7.18.0. The app uses Umi's
      // Declarative Mode and does not enable SSR; the guard below verifies that
      // boundary rather than treating the URL-normalizer patch as a fix.
    },
  ],
  [
    "https://github.com/advisories/GHSA-vfj7-8cjw-p6xm",
    {
      packageName: "braces",
      severity: "high",
      version: "3.0.3",
      paths: [
        "apps__web>@umijs/max>@umijs/lint>@typescript-eslint/eslint-plugin>@typescript-eslint/type-utils>@typescript-eslint/typescript-estree>globby>fast-glob>micromatch>braces",
      ],
    },
  ],
  [
    "https://github.com/advisories/GHSA-hp3w-g68c-fv3c",
    {
      packageName: "sprintf-js",
      severity: "moderate",
      version: "1.0.3",
      paths: [
        "apps__web>@umijs/max>umi>@umijs/test>babel-jest>babel-plugin-istanbul>@istanbuljs/load-nyc-config>js-yaml>argparse>sprintf-js",
      ],
    },
  ],
]);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const severityNames = ["info", "low", "moderate", "high", "critical"];

export function assertOnlyKnownPnpmAuditFindings(report, exitStatus) {
  assert.ok(
    report && typeof report === "object" && !Array.isArray(report),
    "pnpm audit output is not a JSON object",
  );
  assert.equal(Object.hasOwn(report, "error"), false, "pnpm audit returned a top-level error");
  assert.equal(Object.hasOwn(report, "Error"), false, "pnpm audit returned a top-level Error");
  assert.ok(Array.isArray(report.actions), "pnpm audit report has no actions array");
  assert.ok(Array.isArray(report.muted), "pnpm audit report has no muted array");
  assert.ok(
    report.metadata && typeof report.metadata === "object" && !Array.isArray(report.metadata),
    "pnpm audit report has no metadata object",
  );
  for (const field of ["dependencies", "devDependencies", "optionalDependencies", "totalDependencies"]) {
    assert.ok(
      Number.isSafeInteger(report.metadata[field]) && report.metadata[field] >= 0,
      `pnpm audit metadata.${field} must be a non-negative integer`,
    );
  }
  assert.ok(report.metadata.dependencies > 0 && report.metadata.totalDependencies > 0, "pnpm audit metadata must confirm a non-empty scan");
  assert.equal(
    report.metadata.totalDependencies,
    report.metadata.dependencies + report.metadata.devDependencies + report.metadata.optionalDependencies,
    "pnpm audit dependency totals are inconsistent",
  );
  assert.ok(
    report.metadata.vulnerabilities &&
      typeof report.metadata.vulnerabilities === "object" &&
      !Array.isArray(report.metadata.vulnerabilities),
    "pnpm audit report has no vulnerability counts",
  );
  const reportedSeverityNames = Object.keys(report.metadata.vulnerabilities).sort();
  assert.deepEqual(reportedSeverityNames, [...severityNames].sort());
  for (const severity of severityNames) {
    assert.ok(
      Number.isSafeInteger(report.metadata.vulnerabilities[severity]) &&
        report.metadata.vulnerabilities[severity] >= 0,
      `pnpm audit metadata has an invalid ${severity} count`,
    );
  }

  const advisories = report.advisories;
  assert.ok(
    advisories && typeof advisories === "object" && !Array.isArray(advisories),
    "pnpm audit report has no valid advisories object",
  );

  const unexpected = [];
  const observedBySeverity = Object.fromEntries(severityNames.map((severity) => [severity, 0]));
  for (const [id, advisory] of Object.entries(advisories)) {
    assert.ok(advisory && typeof advisory === "object" && !Array.isArray(advisory), `advisory ${id} is malformed`);
    assert.ok(typeof advisory.url === "string", `advisory ${id} has no source URL`);
    assert.ok(typeof advisory.severity === "string" && severityNames.includes(advisory.severity), `advisory ${id} has an invalid severity`);
    assert.ok(Array.isArray(advisory.findings) && advisory.findings.length > 0, `advisory ${id} has no package findings`);
    observedBySeverity[advisory.severity] += advisory.findings.length;
    for (const finding of advisory.findings) {
      assert.ok(
        finding && typeof finding === "object" && !Array.isArray(finding),
        `advisory ${id} has a malformed package finding`,
      );
      assert.ok(typeof finding.version === "string" && finding.version.length > 0, `advisory ${id} has a finding without a version`);
      assert.ok(Array.isArray(finding.paths) && finding.paths.length > 0, `advisory ${id} has a finding without a dependency path`);
      assert.ok(finding.paths.every((dependencyPath) => typeof dependencyPath === "string" && dependencyPath.length > 0), `advisory ${id} has a malformed dependency path`);
    }

    const expected = expectedAdvisories.get(advisory.url);
    if (!expected) {
      unexpected.push({
        id,
        url: advisory.url,
        packageName: advisory.module_name,
        severity: advisory.severity,
      });
      continue;
    }

    assert.equal(
      advisory.module_name,
      expected.packageName,
      `${advisory.url} package changed`,
    );
    assert.equal(
      advisory.severity,
      expected.severity,
      `${advisory.url} severity changed`,
    );
    const versions = new Set();
    const paths = new Set();
    for (const finding of advisory.findings) {
      versions.add(finding.version);
      for (const dependencyPath of finding.paths) paths.add(dependencyPath);
    }
    assert.deepEqual([...versions].sort(), [expected.version]);
    assert.deepEqual(
      [...paths].sort(),
      [...expected.paths].sort(),
      `${advisory.url} dependency path changed`,
    );
  }

  assert.deepEqual(
    unexpected,
    [],
    "pnpm audit found a new or unreviewed advisory",
  );
  assert.deepEqual(
    report.metadata.vulnerabilities,
    observedBySeverity,
    "pnpm audit metadata counts do not match the advisory findings",
  );
  const expectedExitStatus = Object.values(observedBySeverity).some((count) => count > 0) ? 1 : 0;
  assert.equal(exitStatus, expectedExitStatus, "pnpm audit exit status does not match its complete report");
}

export async function verifyPnpmAudit() {
  verifyDependencyPatchIntegrity();
  verifyBracesMitigation();
  verifySprintfMitigation();
  verifyRouterMitigation();
  await verifyActiveMakoDependencyResolution();

  const result = spawnSync("pnpm", ["audit", "--json"], {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  assert.ok(
    [0, 1].includes(result.status),
    `pnpm audit exited with status ${result.status}: ${result.stderr}`,
  );

  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch (error) {
    throw new Error(
      `pnpm audit did not return JSON: ${result.stderr || result.stdout}`,
      { cause: error },
    );
  }
  assertOnlyKnownPnpmAuditFindings(report, result.status);
  return Object.keys(report.advisories ?? {}).length;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const count = await verifyPnpmAudit();
  console.log(
    `pnpm audit passed the exact advisory/path gate (${count} known advisory record(s)).`,
  );
}
