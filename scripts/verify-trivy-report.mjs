import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  verifyBracesMitigation,
  verifyDependencyPatchIntegrity,
} from "./dependency-patch-checks.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const allowedAdvisory = "GHSA-vfj7-8cjw-p6xm";
const allowedIds = new Set([allowedAdvisory, "CVE-2026-93687"]);
const allowedPackage = "braces";
const allowedVersion = "3.0.3";
const expectedTargets = new Map([
  ["backend/pom.xml", "pom"],
  ["plugins/pom.xml", "pom"],
  ["pnpm-lock.yaml", "pnpm"],
]);
const expectedScannerVersion = "0.75.0";

function assertNonEmptyString(value, field) {
  assert.ok(typeof value === "string" && value.length > 0, `Trivy report has no valid ${field}`);
}

export function assertValidTrivyReport(report) {
  assert.ok(report && typeof report === "object" && !Array.isArray(report), "Trivy report is not a JSON object");
  assert.equal(Object.hasOwn(report, "Error"), false, "Trivy returned a top-level Error");
  assert.equal(Object.hasOwn(report, "error"), false, "Trivy returned a top-level error");
  assert.equal(report.SchemaVersion, 2, "Trivy report has an unsupported schema version");
  assert.ok(report.Trivy && typeof report.Trivy === "object", "Trivy report has no scanner metadata");
  assert.equal(report.Trivy.Version, expectedScannerVersion, "Trivy scanner version changed; review the report schema before upgrading it");
  assertNonEmptyString(report.ReportID, "report ID");
  assertNonEmptyString(report.CreatedAt, "scan timestamp");
  assert.ok(Number.isFinite(Date.parse(report.CreatedAt)), "Trivy scan timestamp is invalid");
  assertNonEmptyString(report.ArtifactID, "artifact ID");
  assert.equal(report.ArtifactName, ".", "Trivy report is not for the repository root");
  assert.equal(report.ArtifactType, "repository", "Trivy did not scan a repository artifact");
  assert.ok(report.Metadata && typeof report.Metadata === "object" && !Array.isArray(report.Metadata), "Trivy report has no metadata object");
  for (const field of ["RepoURL", "Commit"]) {
    assertNonEmptyString(report.Metadata[field], `repository metadata ${field}`);
  }
  assert.ok(
    report.Metadata.Branch === undefined || typeof report.Metadata.Branch === "string",
    "Trivy repository metadata Branch must be a string when present",
  );
  assert.ok(Array.isArray(report.Results) && report.Results.length > 0, "Trivy report has no scan results");

  const targets = new Map();
  for (const result of report.Results) {
    assert.ok(result && typeof result === "object" && !Array.isArray(result), "Trivy result is malformed");
    assert.equal(Object.hasOwn(result, "Error"), false, `Trivy result ${result.Target ?? "<unknown>"} returned an Error`);
    assert.equal(Object.hasOwn(result, "error"), false, `Trivy result ${result.Target ?? "<unknown>"} returned an error`);
    assertNonEmptyString(result.Target, "result target");
    assert.equal(targets.has(result.Target), false, `Trivy scanned ${result.Target} more than once`);
    assert.equal(result.Class, "lang-pkgs", `Trivy result ${result.Target} is not a language package scan`);
    assert.ok(expectedTargets.has(result.Target), `Trivy returned an unexpected target: ${result.Target}`);
    assert.equal(result.Type, expectedTargets.get(result.Target), `Trivy result type changed for ${result.Target}`);
    assert.ok(Array.isArray(result.Packages) && result.Packages.length > 0, `Trivy scanned no packages in ${result.Target}`);
    for (const pkg of result.Packages) {
      assert.ok(pkg && typeof pkg === "object" && !Array.isArray(pkg), `Trivy result ${result.Target} has a malformed package`);
      for (const field of ["ID", "Name", "Version"]) {
        assertNonEmptyString(pkg[field], `package ${field} in ${result.Target}`);
      }
    }
    assert.ok(
      result.Vulnerabilities === undefined ||
        result.Vulnerabilities === null ||
        Array.isArray(result.Vulnerabilities),
      `Trivy result ${result.Target} has malformed vulnerabilities`,
    );
    for (const vulnerability of result.Vulnerabilities ?? []) {
      assert.ok(vulnerability && typeof vulnerability === "object" && !Array.isArray(vulnerability), `Trivy result ${result.Target} has a malformed vulnerability`);
      for (const field of ["VulnerabilityID", "PkgName", "InstalledVersion", "Severity"]) {
        assertNonEmptyString(vulnerability[field], `vulnerability ${field}`);
      }
    }
    targets.set(result.Target, result);
  }

  for (const target of expectedTargets.keys()) {
    assert.ok(targets.has(target), `Trivy scan omitted required target ${target}`);
  }
  return targets;
}

export function highSeverityFindings(report) {
  assertValidTrivyReport(report);
  return report.Results.flatMap((result) =>
    (result.Vulnerabilities ?? []).map((vulnerability) => ({
      ...vulnerability,
      Target: result.Target,
    })),
  ).filter((vulnerability) =>
    ["HIGH", "CRITICAL"].includes(String(vulnerability.Severity).toUpperCase()),
  );
}

function isVerifiedBracesFinding(vulnerability) {
  const identifiers = [
    vulnerability.VulnerabilityID,
    ...(vulnerability.Aliases ?? []),
    ...(vulnerability.VendorIDs ?? []),
  ];
  return (
    identifiers.some((identifier) => allowedIds.has(identifier)) &&
    vulnerability.PkgName === allowedPackage &&
    vulnerability.InstalledVersion === allowedVersion &&
    String(vulnerability.Severity).toUpperCase() === "HIGH" &&
    vulnerability.Target === "pnpm-lock.yaml"
  );
}

export function assertOnlyVerifiedFindings(report) {
  verifyDependencyPatchIntegrity();
  verifyBracesMitigation();

  const findings = highSeverityFindings(report);
  const unexpected = findings.filter(
    (vulnerability) => !isVerifiedBracesFinding(vulnerability),
  );

  assert.deepEqual(
    unexpected.map(
      ({
        VulnerabilityID,
        PkgName,
        InstalledVersion,
        Severity,
        PkgPath,
        Target,
      }) => ({
        VulnerabilityID,
        PkgName,
        InstalledVersion,
        Severity,
        PkgPath,
        Target,
      }),
    ),
    [],
    "Trivy found a new HIGH/CRITICAL advisory outside the source-patched braces finding",
  );
  assert.ok(findings.length <= 1, "Trivy returned more than one source-patched braces finding");
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const reportPath = path.resolve(root, process.argv[2] ?? "");
  assert.ok(
    process.argv[2],
    "usage: node scripts/verify-trivy-report.mjs <trivy-json-report>",
  );
  assertOnlyVerifiedFindings(JSON.parse(readFileSync(reportPath, "utf8")));
  console.log(
    `Trivy HIGH/CRITICAL findings passed the exact ${allowedAdvisory} source-patch gate.`,
  );
}
