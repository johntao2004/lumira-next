import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  verifyBracesMitigation,
  verifyDependencyPatchIntegrity,
  verifyActiveMakoDependencyResolution,
  verifyRouterMitigation,
  verifyRouterHydrationBoundary,
  verifySprintfMitigation,
} from "../scripts/dependency-patch-checks.mjs";
import {
  assertOnlyVerifiedFindings,
  assertValidTrivyReport,
} from "../scripts/verify-trivy-report.mjs";
import { assertOnlyKnownPnpmAuditFindings } from "../scripts/verify-pnpm-audit.mjs";

const fixture = (name) =>
  JSON.parse(
    readFileSync(new URL(`./fixtures/security/${name}`, import.meta.url), "utf8"),
  );

test("source patch hashes match the frozen lockfile and installed package copies", () => {
  verifyDependencyPatchIntegrity();
});

test("braces rejects pathological nesting while preserving ordinary expansion", () => {
  verifyBracesMitigation();
});

test("sprintf-js bounds precision and width while preserving ordinary formatting", () => {
  verifySprintfMitigation();
});

test("router URL normalizer backport is present in both installed module formats", () => {
  verifyRouterMitigation();
});

test("the configured Mako bundler resolves the patched esbuild and avoids webpack Babel core", async () => {
  await verifyActiveMakoDependencyResolution();
});

test("GHSA-337j stays outside the app's disabled React Router SSR hydration path", () => {
  verifyRouterHydrationBoundary();
});

test("Trivy accepts complete scanner fixtures, including a zero-finding scan", () => {
  const clean = fixture("trivy-fs-clean-scan.json");
  assert.equal(assertValidTrivyReport(clean).size, 3);
  assert.doesNotThrow(() => assertOnlyVerifiedFindings(clean));

  const detachedCheckout = structuredClone(clean);
  delete detachedCheckout.Metadata.Branch;
  assert.equal(assertValidTrivyReport(detachedCheckout).size, 3);

  assert.doesNotThrow(() =>
    assertOnlyVerifiedFindings(fixture("trivy-fs-verified-braces.json")),
  );
});

test("Trivy rejects errors, empty/incomplete scans, and unreviewed findings", () => {
  const clean = fixture("trivy-fs-clean-scan.json");

  const topLevelError = structuredClone(clean);
  topLevelError.Error = "scanner failed";
  assert.throws(() => assertValidTrivyReport(topLevelError), /top-level Error/);

  const resultError = structuredClone(clean);
  resultError.Results[0].Error = "target failed";
  assert.throws(() => assertValidTrivyReport(resultError), /returned an Error/);

  const malformedBranch = structuredClone(clean);
  malformedBranch.Metadata.Branch = { name: "main" };
  assert.throws(
    () => assertValidTrivyReport(malformedBranch),
    /Branch must be a string when present/,
  );

  const emptyScan = structuredClone(clean);
  emptyScan.Results = [];
  assert.throws(() => assertValidTrivyReport(emptyScan), /no scan results/);

  const missingTarget = structuredClone(clean);
  missingTarget.Results.pop();
  assert.throws(() => assertValidTrivyReport(missingTarget), /omitted required target/);

  const malformedResult = structuredClone(clean);
  malformedResult.Results[0].Packages = [];
  assert.throws(() => assertValidTrivyReport(malformedResult), /scanned no packages/);

  const withNewFinding = fixture("trivy-fs-verified-braces.json");
  withNewFinding.Results.find((result) => result.Target === "pnpm-lock.yaml").Vulnerabilities.push({
    VulnerabilityID: "CVE-2099-00000",
    PkgName: "new-package",
    InstalledVersion: "1.0.0",
    Severity: "CRITICAL",
  });
  assert.throws(
    () => assertOnlyVerifiedFindings(withNewFinding),
    /outside the source-patched braces finding/,
  );
});

test("pnpm audit accepts a real scanner report and a valid zero-vulnerability report", () => {
  const observed = fixture("pnpm-audit-known-findings.json");
  assert.doesNotThrow(() => assertOnlyKnownPnpmAuditFindings(observed, 1));

  const clean = {
    actions: [],
    advisories: {},
    muted: [],
    metadata: {
      vulnerabilities: { info: 0, low: 0, moderate: 0, high: 0, critical: 0 },
      dependencies: 1,
      devDependencies: 0,
      optionalDependencies: 0,
      totalDependencies: 1,
    },
  };
  assert.doesNotThrow(() => assertOnlyKnownPnpmAuditFindings(clean, 0));
});

test("pnpm audit rejects scanner errors, empty scans, and status/report mismatches", () => {
  const clean = {
    actions: [],
    advisories: {},
    muted: [],
    metadata: {
      vulnerabilities: { info: 0, low: 0, moderate: 0, high: 0, critical: 0 },
      dependencies: 1,
      devDependencies: 0,
      optionalDependencies: 0,
      totalDependencies: 1,
    },
  };

  assert.throws(
    () => assertOnlyKnownPnpmAuditFindings({ error: "registry unavailable" }, 0),
    /top-level error/,
  );
  assert.throws(
    () => assertOnlyKnownPnpmAuditFindings({ ...clean, error: "registry unavailable" }, 0),
    /top-level error/,
  );
  assert.throws(
    () => assertOnlyKnownPnpmAuditFindings({ advisories: {} }, 0),
    /actions array/,
  );

  const emptyScan = structuredClone(clean);
  emptyScan.metadata.dependencies = 0;
  emptyScan.metadata.totalDependencies = 0;
  assert.throws(
    () => assertOnlyKnownPnpmAuditFindings(emptyScan, 0),
    /non-empty scan/,
  );
  assert.throws(
    () => assertOnlyKnownPnpmAuditFindings(clean, 1),
    /exit status does not match/,
  );
});

test("pnpm audit rejects new advisories and changed dependency paths", () => {
  const known = fixture("pnpm-audit-known-findings.json");
  const withNew = structuredClone(known);
  withNew.advisories.new = {
    url: "https://github.com/advisories/GHSA-0000-0000-0000",
    module_name: "new-package",
    severity: "critical",
    findings: [{ version: "1.0.0", paths: ["apps__web>new-package"] }],
  };
  withNew.metadata.vulnerabilities.critical += 1;
  assert.throws(
    () => assertOnlyKnownPnpmAuditFindings(withNew, 1),
    /new or unreviewed advisory/,
  );

  const changedPath = structuredClone(known);
  const braces = Object.values(changedPath.advisories).find(
    (advisory) => advisory.module_name === "braces",
  );
  braces.findings[0].paths.push(
    "apps__web>some-new-parent>braces",
  );
  assert.throws(
    () => assertOnlyKnownPnpmAuditFindings(changedPath, 1),
    /dependency path changed/,
  );
});
