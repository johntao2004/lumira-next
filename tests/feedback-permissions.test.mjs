import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const manifest = JSON.parse(readFileSync("plugins/feedback/plugin.json", "utf8"));
const frontendManifest = readFileSync("plugins/feedback/frontend/manifest.ts", "utf8");
const frontendPage = readFileSync("plugins/feedback/frontend/feedback-page.tsx", "utf8");
const localeFiles = ["de", "en", "es", "fr", "ja", "ko", "zh-cn", "zh-tw"];

function apiRule(method, path) {
  const rule = manifest.apiRules.find((candidate) => candidate.method === method && candidate.path === path);
  assert.ok(rule, `missing ${method} ${path} API rule`);
  return rule;
}

test("feedback manifest separates scoped read, create, update and reply permissions", () => {
  assert.equal(manifest.version, "1.0.14");
  assert.match(frontendManifest, /version: "1\.0\.14"/);
  assert.deepEqual(manifest.permissions, [
    "platform:feedback:access",
    "platform:feedback:read",
    "platform:feedback:create",
    "platform:feedback:update",
    "platform:feedback:reply",
  ]);

  const route = manifest.routes.find(({ id }) => id === "feedback");
  assert.equal(route.permission, "platform:feedback:access");
  assert.deepEqual(route.operations, {
    read: "platform:feedback:read",
    create: "platform:feedback:create",
    update: "platform:feedback:update",
    reply: "platform:feedback:reply",
  });
  assert.deepEqual(route.scopedOperations, ["read", "create", "update", "reply"]);
  assert.doesNotMatch(frontendManifest, /read-all/);
  assert.doesNotMatch(JSON.stringify(manifest), /read-all|globalAuthorities/);
});

test("feedback read APIs require access and read; options allow create-only access", () => {
  const readRequirements = ["platform:feedback:access", "platform:feedback:read"];
  for (const path of [
    "/api/feedback",
    "/api/feedback/*",
    "/api/feedback/manage",
    "/api/feedback/manage/*",
    "/api/feedback/images/*",
    "/api/feedback/manage/images/*",
    "/api/feedback/manage/options",
  ]) {
    const rule = apiRule("GET", path);
    assert.deepEqual(rule.authorities, [], `${path} uses all-of requirements`);
    assert.deepEqual(rule.allAuthorities, readRequirements, `${path} requires ACCESS and READ`);
    assert.equal(rule.global, false, `${path} uses scoped grants`);
  }

  const options = apiRule("GET", "/api/feedback/options");
  assert.deepEqual(options.authorities, ["platform:feedback:read", "platform:feedback:create"]);
  assert.deepEqual(options.allAuthorities, ["platform:feedback:access"]);
});

test("feedback updates require page access, read and either scoped operation", () => {
  const update = apiRule("PUT", "/api/feedback/manage/*");
  assert.deepEqual(update.authorities, ["platform:feedback:update", "platform:feedback:reply"]);
  assert.deepEqual(update.allAuthorities, ["platform:feedback:access", "platform:feedback:read"]);
  assert.equal(update.global, false);

  for (const path of ["/api/feedback", "/api/feedback/images"]) {
    const create = apiRule("POST", path);
    assert.deepEqual(create.authorities, []);
    assert.deepEqual(create.allAuthorities, ["platform:feedback:access", "platform:feedback:create"]);
  }
});

test("feedback UI loads managed rows only with READ and gates each action by detail capability", () => {
  assert.match(frontendPage, /enabled: canRead,\s+queryFn:/);
  assert.match(frontendPage, /`\/feedback\/manage\?\$\{params\.toString\(\)\}`/);
  assert.match(frontendPage, /enabled: canRead \|\| canCreate/);
  assert.match(frontendPage, /api<FeedbackCatalog>\("\/feedback\/options"/);
  assert.match(frontendPage, /canRead && canUpdateStatus && detailQuery\.data\?\.canUpdateStatus === true/);
  assert.match(frontendPage, /canRead && canReply && detailQuery\.data\?\.canReply === true/);
  assert.match(frontendPage, /\/feedback\/manage\/images\//);
  assert.match(frontendPage, /"forbidden"/);
  assert.doesNotMatch(frontendPage, /<Tabs|read-all|allFeedback|myFeedback/);
});

test("feedback read and reply labels are neutral in every shipped locale", () => {
  for (const locale of localeFiles) {
    const source = readFileSync(`apps/web/src/platform/i18n/messages/${locale}.ts`, "utf8");
    const read = source.match(/pluginPermissionFeedbackRead: "([^"]+)"/)?.[1];
    const reply = source.match(/pluginPermissionFeedbackReply: "([^"]+)"/)?.[1];
    const fallbackReply = source.match(/permissionReply: "([^"]+)"/)?.[1];
    assert.ok(read, `${locale} has a feedback read label`);
    assert.ok(reply, `${locale} has a feedback reply label`);
    assert.ok(fallbackReply, `${locale} has a neutral fallback reply label`);
    assert.doesNotMatch(read, /own|self|本人|自分|내 |eigenes/i, `${locale} read label is scope-neutral`);
    assert.doesNotMatch(reply, /process|handle|管理|處理|处理|処理|처리|traiter|gestionar|bearbeiten/i, `${locale} reply label describes replying`);
    assert.doesNotMatch(fallbackReply, /process|handle|管理|處理|处理|処理|처리|traiter|gestionar|bearbeiten/i, `${locale} fallback reply label describes replying`);
  }
});
