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

const fixture = (name) => {
  const report = JSON.parse(readFileSync(new URL(`./fixtures/security/${name}`, import.meta.url), "utf8"));
  // 历史真实报告补充明确的合成结构样本，仅测试门禁；不冒充新依赖的扫描结果。
  // Extend historical reports with explicit synthetic schema fixtures for gate tests, never as new dependency-scan evidence.
  if (name.startsWith("trivy-")) for (const target of ["framework/platform-contracts/pom.xml", "framework/plugin-sdk/pom.xml", "framework/payment-sdk/pom.xml", "framework/plugin-build/pom.xml", "examples/independent-notes/pom.xml"]) {
    report.Results.push({ Target: target, Class: "lang-pkgs", Type: "pom", Packages: [{ ID: "synthetic-contract-fixture:0.1.0", Name: "synthetic-contract-fixture", Version: "0.1.0" }] });
  }
  return report;
};

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
  assert.equal(assertValidTrivyReport(clean).size, 8);
  assert.doesNotThrow(() => assertOnlyVerifiedFindings(clean));

  const detachedCheckout = structuredClone(clean);
  delete detachedCheckout.Metadata.Branch;
  assert.equal(assertValidTrivyReport(detachedCheckout).size, 8);

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

// 固定已核实的扫描器版本，保持镜像阻断与源码报告复核两道门禁。
// Pin verified scanner versions and retain both image failure gates and source-report review.
test("CI pins Trivy releases without weakening scan gates", () => {
  const workflow = readFileSync(".github/workflows/ci.yml", "utf8");
  assert.equal((workflow.match(/aquasecurity\/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25/g) ?? []).length, 6);
  assert.equal((workflow.match(/version: v0\.75\.0/g) ?? []).length, 6);
  assert.equal((workflow.match(/exit-code: "1"/g) ?? []).length, 5);
  assert.match(workflow, /node scripts\/verify-trivy-report\.mjs/);
  assert.match(workflow, /name: Populate Maven dependency cache/);
  assert.match(workflow, /image-ref: lumira-database:ci/);
  assert.match(workflow, /docker image inspect lumira-database:ci/);
  assert.match(workflow, /docker builder prune --all --force/);
  assert.doesNotMatch(workflow, /docker (?:system|volume) prune/);
  assert.match(workflow, /skip-dirs: \.runtime,\*\*\/target/);
});

// 镜像中的 Caddy 必须重建到补丁依赖，不能仅忽略扫描告警。
// Rebuild Caddy with the patched dependency rather than suppressing scanner findings.
test("Caddy build pins the HTTP/2 denial-of-service fix", () => {
  const dockerfile = readFileSync("deploy/web.Dockerfile", "utf8");
  assert.match(dockerfile, /--replace golang\.org\/x\/net=golang\.org\/x\/net@v0\.60\.0/);
  assert.match(dockerfile, /go version -m \/out\/caddy/);
});

// 两个入口都必须继承补丁基础阶段，网关也必须独立验证与扫描。
// Both entry points must inherit the patched base; validate and scan the gateway independently.
test("edge deployment uses the patched target and remains covered by CI", () => {
  const dockerfile = readFileSync("deploy/web.Dockerfile", "utf8");
  const compose = readFileSync("deploy/compose.yml", "utf8");
  const workflow = readFileSync(".github/workflows/ci.yml", "utf8");
  assert.match(dockerfile, /FROM patched-caddy AS edge/);
  assert.match(dockerfile, /FROM patched-caddy AS web/);
  assert.match(compose, /edge:\n    build:\n      context: \.\.\n      dockerfile: deploy\/web\.Dockerfile\n      target: edge/);
  assert.doesNotMatch(compose, /image: caddy:/);
  assert.match(workflow, /--target edge -t lumira-edge:ci/);
  assert.match(workflow, /lumira-edge:ci caddy validate/);
  assert.match(workflow, /image-ref: lumira-edge:ci/);
});

// 部署必须显式传入持久密钥，并把初始化管理员留在数据库容器内。
// Deployment explicitly supplies persistent keys and confines bootstrap credentials to the database container.
test("production deployment separates bootstrap credentials and enforces role checks", () => {
  const compose = readFileSync("deploy/compose.yml", "utf8");
  const server = compose.split("  server:\n")[1].split("  office-worker:\n")[0];
  assert.match(compose, /POSTGRES_USER: lumira_bootstrap/);
  assert.match(compose, /10-application-role\.sh/);
  assert.match(server, /LUMIRA_DB_REQUIRE_UNPRIVILEGED: "true"/);
  assert.match(server, /LUMIRA_SECRET_KEYS: \$\{LUMIRA_SECRET_KEYS:\?/);
  assert.match(server, /LUMIRA_SECRET_ACTIVE_KEY:/);
  assert.match(server, /LUMIRA_DB_MIGRATION_USER: lumira_migrator/);
  assert.match(server, /LUMIRA_DB_MIGRATION_PASSWORD:/);
  assert.doesNotMatch(server, /LUMIRA_DB_BOOTSTRAP_PASSWORD/);
});

// 数据库降权工具必须真实重建，不能跳过漏洞或测试密钥扫描。
// Rebuild the database privilege-drop tool without skipping vulnerability or secret scanning.
test('database image pins upstream gosu and preserves production PostgreSQL entrypoint', () => {
  const image = readFileSync(new URL('../deploy/database.Dockerfile', import.meta.url), 'utf8');
  const compose = readFileSync(new URL('../deploy/compose.yml', import.meta.url), 'utf8');
  const browser = readFileSync(new URL('../scripts/run-ci-browser.sh', import.meta.url), 'utf8');
  assert.match(image, /FROM golang:1\.26\.9-alpine3\.23/);
  assert.match(image, /6456aaa0f3c854d199d0f037f068eb97515b7513/);
  assert.match(image, /go mod verify/);
  assert.match(image, /-mod=readonly/);
  assert.match(image, /FROM postgres:18\.6@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336/);
  assert.match(image, /--chmod=0755/);
  assert.doesNotMatch(image, /^(ENTRYPOINT|CMD|USER|STOPSIGNAL) /m);
  assert.match(image, /rm -f \/etc\/ssl\/private\/ssl-cert-snakeoil.key \/etc\/ssl\/certs\/ssl-cert-snakeoil.pem/);
  assert.match(compose, /dockerfile: deploy\/database.Dockerfile/);
  assert.match(browser, /database:\n    image: lumira-database:ci/);
});
