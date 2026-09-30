import assert from "node:assert/strict";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const privatePath = ".runtime/ordinary-qa-private.json";
let privateConfig = JSON.parse(readFileSync(privatePath, "utf8"));
const origin = new URL(privateConfig.frontendUrl).origin;
const runDir = ".runtime/ordinary-user-closeout";
mkdirSync(runDir, { recursive: true, mode: 0o700 });
const priorResultsPath = `${runDir}/results.json`;
const priorResults = existsSync(priorResultsPath) ? JSON.parse(readFileSync(priorResultsPath, "utf8")) : null;
const resume = ["failed", "partial"].includes(priorResults?.status) && priorResults?.marker && privateConfig.marker === priorResults.marker;
if (resume && priorResults) {
  const historyPath = `${runDir}/attempt-history.json`;
  const history = existsSync(historyPath) ? JSON.parse(readFileSync(historyPath, "utf8")) : [];
  history.push({
    recordedAt: new Date().toISOString(),
    status: priorResults.status,
    failedAt: priorResults.failedAt ?? null,
    failureType: priorResults.failureType ?? null,
    failure: priorResults.failure ?? null,
    checkCount: priorResults.checks?.length ?? 0,
    checkNames: [...new Set((priorResults.checks ?? []).map(check => check.name))],
  });
  writeFileSync(historyPath, `${JSON.stringify(history, null, 2)}\n`, { mode: 0o600 });
  chmodSync(historyPath, 0o600);
}
const tag = resume ? priorResults.marker.slice(2) : randomUUID().replaceAll("-", "").slice(0, 20);
const marker = `ou${tag}`;
const ordinary = {
  username: resume ? privateConfig.ordinaryUsername : `qa_${marker}`,
  password: resume ? privateConfig.ordinaryPassword : randomBytes(24).toString("base64url"),
  displayName: resume ? privateConfig.ordinaryDisplayName : `QA Ordinary ${tag.slice(0, 8)}`,
};
const peer = {
  username: resume ? privateConfig.peerUsername : `qp_${marker}`,
  password: resume ? privateConfig.peerPassword : randomBytes(24).toString("base64url"),
  displayName: resume ? privateConfig.peerDisplayName : `QA Peer ${tag.slice(0, 8)}`,
};
const roleCodes = {
  base: `${marker}_base`,
  storage: `${marker}_storage`,
  count: `${marker}_count`,
  stricter: `${marker}_strict`,
};
const dictionaryCode = `${marker}_dict`;
const dictionaryName = `QA Profile ${tag.slice(0, 8)}`;
const dictionaryItemValue = `choice_${tag.slice(0, 8)}`;
const dictionaryItemLabel = `QA choice ${tag.slice(0, 8)}`;
const selectFieldLabel = `QA select ${tag.slice(0, 8)}`;
const quotaScenarioNames = new Set([
  "storage-only",
  "count-only",
  "storage-and-count",
  "strict-storage-and-three-file-minimum",
  "disabled-strict-role-ignored",
  "unassigned-strict-role-ignored",
  "no-role-limits-defaults",
]);
const results = resume ? {
  ...priorResults,
  checks: [...new Map((priorResults.checks ?? []).map(check => {
    const quotaScenario = check.quotaScenario ?? (quotaScenarioNames.has(check.name) ? check.name : undefined);
    const name = quotaScenario ? `role quota ${quotaScenario}` : check.name;
    return [name, quotaScenario ? { ...check, name, quotaScenario } : { ...check, name }];
  })).values()],
} : { marker, roleCodes, users: {}, checks: [] };
const hasCheck = name => results.checks.some(check => check.name === name);
const hasQuotaCheck = name => hasCheck(`role quota ${name}`) || hasCheck(name);
let stage = "browser launch";
let failure = null;

function savePrivateFixture(extra = {}) {
  const next = {
    ...privateConfig,
    ...extra,
    ordinaryUsername: ordinary.username,
    ordinaryPassword: ordinary.password,
    ordinaryDisplayName: ordinary.displayName,
    peerUsername: peer.username,
    peerPassword: peer.password,
    peerDisplayName: peer.displayName,
    roleCodes,
  };
  const tmp = `${privatePath}.tmp`;
  writeFileSync(tmp, JSON.stringify(next), { mode: 0o600 });
  chmodSync(tmp, 0o600);
  renameSync(tmp, privatePath);
  chmodSync(privatePath, 0o600);
  Object.assign(privateConfig, next);
}

function saveResults() {
  writeFileSync(`${runDir}/results.json`, `${JSON.stringify(results, null, 2)}\n`, { mode: 0o600 });
  chmodSync(`${runDir}/results.json`, 0o600);
}

function redact(value) {
  return String(value)
    .replaceAll(privateConfig.adminPassword ?? "\0", "[redacted]")
    .replaceAll(ordinary.password, "[redacted]")
    .replaceAll(peer.password, "[redacted]");
}

function note(name, detail = {}) {
  results.checks = results.checks.filter(check => check.name !== name);
  results.checks.push({ name, ...detail });
  saveResults();
  process.stdout.write(`PASS ${name}\n`);
}

function markPartial(next) {
  results.status = "partial";
  results.next = next;
  delete results.failedAt;
  delete results.failureType;
  delete results.failure;
  saveResults();
}

function apiUrl(path) {
  return new URL(`/api${path}`, origin).href;
}

async function getJson(context, path, expectedStatus = 200) {
  const response = await context.request.get(apiUrl(path));
  assert.equal(response.status(), expectedStatus, `GET ${path} status`);
  if (expectedStatus === 204) return null;
  return response.json();
}

async function loginThroughUi(browser, username, password) {
  const context = await browser.newContext({ baseURL: origin, viewport: { width: 1280, height: 900 }, acceptDownloads: true });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.setDefaultNavigationTimeout(20000);
  await page.goto("/user/login", { waitUntil: "domcontentloaded" });
  await page.getByLabel("用户名", { exact: true }).waitFor();
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password);
  const responsePromise = page.waitForResponse(response =>
    new URL(response.url()).pathname === "/api/auth/login" && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: /登\s*录/ }).click();
  const response = await responsePromise;
  assert.equal(response.status(), 200, "UI login status");
  await page.waitForURL(url => url.pathname.startsWith("/dashboard/"), { timeout: 20000 });
  return { context, page };
}

async function permissionCard(form, title) {
  const cards = form.locator(".ant-card");
  const index = await cards.evaluateAll((nodes, target) => nodes.findIndex(node =>
    node.querySelector(".ant-card-head-title")?.textContent?.trim() === target,
  ), title);
  assert.notEqual(index, -1, `permission card ${title}`);
  return cards.nth(index);
}

async function createRole(page, { code, name, storageGiB = null, fileCount = null, includeUserRead = false, includeFeedback = false }) {
  stage = `create role ${code} through UI`;
  await page.goto("/dashboard/roles", { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "新增角色", exact: true }).click();
  const form = page.locator("form#role-editor");
  await form.waitFor();
  await form.locator("#code").fill(code);
  await form.locator("#name").fill(name);
  if (storageGiB !== null) await form.locator("#fileQuotaStorageGiB").fill(String(storageGiB));
  if (fileCount !== null) await form.locator("#fileQuotaFilesLimit").fill(String(fileCount));
  if (includeUserRead) {
    const users = await permissionCard(form, "用户管理");
    await users.getByRole("checkbox").first().check();
  }
  if (includeFeedback) {
    const feedback = await permissionCard(form, "工单反馈");
    await feedback.getByRole("checkbox", { name: "页面访问", exact: true }).check();
    await feedback.getByRole("checkbox", { name: "提交反馈", exact: true }).check();
  }
  const created = page.waitForResponse(response =>
    new URL(response.url()).pathname === "/api/platform/roles" && response.request().method() === "POST",
  );
  await page.locator('button[form="role-editor"]').click();
  const response = await created;
  assert.equal(response.status(), 201, `create role ${code}`);
  await form.waitFor({ state: "hidden" });
  const result = await getJson(page.context(), `/platform/roles?page=1&pageSize=100`);
  const role = result.items.find(item => item.code === code);
  assert.ok(role, `created role ${code} in list`);
  return getJson(page.context(), `/platform/roles/${role.id}`);
}

async function createUser(page, user) {
  stage = `create user ${user.username} through UI`;
  await page.goto("/dashboard/users", { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "新增用户", exact: true }).click();
  const form = page.locator("form#user-editor");
  await form.waitFor();
  await form.locator("#username").fill(user.username);
  await form.locator("#displayName").fill(user.displayName);
  await form.locator("#password").fill(user.password);
  const created = page.waitForResponse(response =>
    new URL(response.url()).pathname === "/api/platform/users" && response.request().method() === "POST",
  );
  await page.locator('button[form="user-editor"]').click();
  const response = await created;
  assert.equal(response.status(), 201, `create user ${user.username}`);
  await form.waitFor({ state: "hidden" });
  const result = await getJson(page.context(), "/platform/users?page=1&pageSize=100");
  const createdUser = result.items.find(item => item.username === user.username);
  assert.ok(createdUser, `created user ${user.username} in list`);
  return createdUser;
}

async function setUserRoleCodes(page, username, allQaRoles, desiredCodes) {
  stage = `assign roles for ${username} through UI`;
  await page.goto("/dashboard/users", { waitUntil: "domcontentloaded" });
  const userRows = page.locator(".ant-table-row").filter({ hasText: username });
  await userRows.first().waitFor();
  await userRows.first().getByRole("button", { name: "编辑", exact: true }).click();
  const form = page.locator("form#user-editor");
  await form.waitFor();
  await page.getByRole("tab", { name: "分配角色", exact: true }).click();
  const users = await getJson(page.context(), "/platform/users?page=1&pageSize=100");
  const user = users.items.find(item => item.username === username);
  assert.ok(user, `user lookup ${username}`);
  const assignment = await getJson(page.context(), `/platform/users/${user.id}/roles`);
  const roles = await getJson(page.context(), "/platform/roles?page=1&pageSize=100");
  const idsByCode = new Map(roles.items.map(role => [role.code, role.id]));
  for (const code of desiredCodes) assert.ok(idsByCode.has(code), `role exists ${code}`);
  const currentQaCodes = allQaRoles.filter(role => assignment.roleIds.includes(role.id)).map(role => role.code);
  const changes = new Set([...currentQaCodes, ...desiredCodes]);
  const wanted = new Set(desiredCodes);
  const changedCodes = [...changes].filter(code => currentQaCodes.includes(code) !== wanted.has(code));
  for (const code of changedCodes) {
    const shouldBeSelected = wanted.has(code);
    if (currentQaCodes.includes(code) === shouldBeSelected) continue;
    const searchForm = form.getByRole("search");
    const search = searchForm.locator("#code");
    await search.fill(code);
    await searchForm.getByRole("button", { name: /查\s*询/ }).click();
    const row = form.locator(".ant-table-row").filter({ hasText: code });
    await row.first().waitFor();
    const checkbox = row.first().getByRole("checkbox");
    if (shouldBeSelected) await checkbox.check();
    else await checkbox.uncheck();
  }
  const roleIds = desiredCodes.map(code => idsByCode.get(code));
  const previewParams = new URLSearchParams();
  roleIds.forEach(id => previewParams.append("roleIds", id));
  const preview = await getJson(page.context(), `/platform/users/${user.id}/file-quota/preview?${previewParams}`);
  const dialogText = await form.innerText();
  if (changedCodes.length === 0) {
    await page.getByRole("button", { name: /取\s*消/ }).last().click();
    await form.waitFor({ state: "hidden" });
    return { userId: user.id, roleIds, preview, dialogText };
  }
  const update = page.waitForResponse(response =>
    new URL(response.url()).pathname === `/api/platform/users/${user.id}/editor` && response.request().method() === "PUT",
  );
  await page.locator('button[form="user-editor"]').click();
  const confirmation = page.getByRole("button", { name: /确\s*认/ }).last();
  if (await confirmation.isVisible().catch(() => false)) await confirmation.click();
  const response = await update;
  assert.equal(response.status(), 200, `role assignment for ${username}`);
  await form.waitFor({ state: "hidden" });
  return { userId: user.id, roleIds, preview, dialogText };
}

async function setRoleEnabled(page, roleId, code, enabled) {
  stage = `set role ${code} enabled=${enabled} through UI`;
  await page.goto("/dashboard/roles", { waitUntil: "domcontentloaded" });
  const row = page.locator(".ant-table-row").filter({ hasText: code });
  await row.first().waitFor();
  await row.first().getByRole("button", { name: "编辑", exact: true }).click();
  const form = page.locator("form#role-editor");
  await form.waitFor();
  const toggle = form.getByRole("switch").first();
  const current = (await toggle.getAttribute("aria-checked")) === "true";
  if (current === enabled) {
    await page.getByRole("button", { name: /取\s*消/ }).last().click();
    await form.waitFor({ state: "hidden" });
    return getJson(page.context(), `/platform/roles/${roleId}`);
  }
  await toggle.click();
  const update = page.waitForResponse(response =>
    new URL(response.url()).pathname === `/api/platform/roles/${roleId}` && response.request().method() === "PUT",
  );
  await page.locator('button[form="role-editor"]').click();
  const confirmation = page.getByRole("button", { name: /确\s*认/ }).last();
  if (await confirmation.isVisible().catch(() => false)) await confirmation.click();
  const response = await update;
  assert.equal(response.status(), 200, `toggle role ${code}`);
  await form.waitFor({ state: "hidden" });
  return getJson(page.context(), `/platform/roles/${roleId}`);
}

function quotaDisplay(bytes) {
  const unit = bytes >= 1024 ** 3 ? 3 : bytes >= 1024 ** 2 ? 2 : bytes >= 1024 ? 1 : 0;
  return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(bytes / 1024 ** unit)} ${["B", "KiB", "MiB", "GiB"][unit]}`;
}

async function assertEffectiveQuota(context, page, name, expectedBytes, expectedCount, dialogText = "") {
  stage = `verify quota ${name}`;
  const usage = await getJson(context, "/account/files/usage");
  assert.equal(usage.maximumBytes, expectedBytes, `${name}: bytes`);
  assert.equal(usage.maximumFiles, expectedCount, `${name}: count`);
  if (dialogText) assert.ok(dialogText.includes(quotaDisplay(expectedBytes)), `${name}: quota preview visible in user editor`);
  const detail = {
    quotaScenario: name,
    maximumBytes: usage.maximumBytes,
    maximumFiles: usage.maximumFiles,
    previewContainsStorage: dialogText ? dialogText.includes(quotaDisplay(expectedBytes)) : null,
  };
  note(`role quota ${name}`, detail);
  return usage;
}

async function ensureRole(page, roles, code, name, quota = {}) {
  const list = await getJson(page.context(), "/platform/roles?page=1&pageSize=100");
  const summary = list.items.find(item => item.code === code);
  const role = summary
    ? await getJson(page.context(), `/platform/roles/${summary.id}`)
    : await createRole(page, { code, name, ...quota });
  if (!roles.some(item => item.id === role.id)) roles.push(role);
  results.roles = roles.map(({ id, code: roleCode, enabled, dataScope, fileQuotaBytesLimit, fileQuotaFilesLimit, authorities }) => ({
    id, code: roleCode, enabled, dataScope, fileQuotaBytesLimit, fileQuotaFilesLimit, authorities,
  }));
  saveResults();
  savePrivateFixture({ marker, roleCodes, qaRoleIds: roles.map(item => item.id) });
  return role;
}

async function ensureDictionaryAndItem(page) {
  stage = "create SELECT dictionary and item through UI";
  let dictionaries = await getJson(page.context(), "/platform/dictionaries?page=1&pageSize=100");
  let dictionary = dictionaries.items.find(item => item.code === dictionaryCode);
  if (!dictionary) {
    await page.goto("/dashboard/dictionaries", { waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: "新增字典", exact: true }).click();
    const form = page.locator("form#dictionary-editor");
    await form.waitFor();
    await form.locator("#code").fill(dictionaryCode);
    await form.locator("#name").fill(dictionaryName);
    const created = page.waitForResponse(response =>
      new URL(response.url()).pathname === "/api/platform/dictionaries" && response.request().method() === "POST",
    );
    await page.locator('button[form="dictionary-editor"]').click();
    const response = await created;
    assert.equal(response.status(), 201, "dictionary created through UI");
    await form.waitFor({ state: "hidden" });
    dictionaries = await getJson(page.context(), "/platform/dictionaries?page=1&pageSize=100");
    dictionary = dictionaries.items.find(item => item.code === dictionaryCode);
  }
  assert.ok(dictionary, "SELECT dictionary exists");
  let items = await getJson(page.context(), `/platform/dictionaries/${dictionary.id}/items?page=1&pageSize=100`);
  let item = items.items.find(candidate => candidate.value === dictionaryItemValue);
  if (!item) {
    await page.goto("/dashboard/dictionaries", { waitUntil: "domcontentloaded" });
    const row = page.locator(".ant-table-row").filter({ hasText: dictionaryCode });
    await row.first().waitFor();
    await row.first().getByRole("button", { name: "字典项", exact: true }).click();
    await page.getByRole("button", { name: "新增字典项", exact: true }).click();
    const form = page.locator("form#dictionary-item-editor");
    await form.waitFor();
    await form.locator("#value").fill(dictionaryItemValue);
    await form.locator("#label").fill(dictionaryItemLabel);
    const created = page.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/platform/dictionaries/${dictionary.id}/items` && response.request().method() === "POST",
    );
    await page.locator('button[form="dictionary-item-editor"]').click();
    const response = await created;
    assert.equal(response.status(), 201, "dictionary item created through UI");
    await form.waitFor({ state: "hidden" });
    items = await getJson(page.context(), `/platform/dictionaries/${dictionary.id}/items?page=1&pageSize=100`);
    item = items.items.find(candidate => candidate.value === dictionaryItemValue);
  }
  assert.ok(item, "SELECT dictionary item exists");
  return { dictionary, item };
}

async function ensureSelectField(page, dictionary) {
  stage = "create SELECT profile field through UI";
  const settingsPath = "/platform/settings/profile-fields";
  let snapshot = await getJson(page.context(), settingsPath);
  let field = snapshot.data.fields.find(candidate => candidate.key === results.selectField?.key)
    ?? snapshot.data.fields.find(candidate => candidate.label === selectFieldLabel && candidate.dictionaryCode === dictionary.code);
  if (!field) {
    await page.goto("/dashboard/profile-fields", { waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: "新增资料字段", exact: true }).waitFor();
    const index = snapshot.data.fields.length;
    await page.getByRole("button", { name: "新增资料字段", exact: true }).click();
    const item = page.locator(".ant-collapse-item").last();
    await item.locator(".ant-collapse-header").click();
    const keyInput = item.locator(`#data_fields_${index}_key`);
    await keyInput.waitFor();
    const key = await keyInput.inputValue();
    await item.locator(`#data_fields_${index}_label`).fill(selectFieldLabel);
    await item.locator(`#data_fields_${index}_type`).click();
    await chooseVisibleSelectOption(page, "单选");
    const dictionarySelect = item.locator(".ant-select").nth(1);
    await dictionarySelect.waitFor({ state: "visible" });
    await dictionarySelect.click();
    await chooseVisibleSelectOption(page, `${dictionary.name} (${dictionary.code})`);
    const saved = page.waitForResponse(response =>
      new URL(response.url()).pathname === `/api${settingsPath}` && response.request().method() === "PUT",
    );
    await page.locator("form.settings-form button[type=submit]").click();
    const response = await saved;
    assert.equal(response.status(), 200, "profile SELECT field saved through UI");
    snapshot = await response.json();
    field = snapshot.data.fields.find(candidate => candidate.key === key);
  }
  assert.ok(field, "SELECT profile field exists");
  assert.equal(field.type, "SELECT");
  assert.equal(field.dictionaryCode, dictionary.code);
  results.selectField = { key: field.key, label: field.label, dictionaryCode: field.dictionaryCode };
  saveResults();
  savePrivateFixture({
    marker,
    roleCodes,
    dictionaryCode: dictionary.code,
    dictionaryName: dictionary.name,
    dictionaryId: dictionary.id,
    dictionaryItemId: results.dictionaryItemId,
    dictionaryItemValue,
    dictionaryItemLabel,
    selectFieldKey: field.key,
    selectFieldLabel: field.label,
  });

  await page.goto("/dashboard/profile-fields", { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "新增资料字段", exact: true }).waitFor();
  const fields = await getJson(page.context(), settingsPath);
  const index = fields.data.fields.findIndex(candidate => candidate.key === field.key);
  assert.notEqual(index, -1, "saved SELECT field survives reload");
  await page.locator(".ant-collapse-item").nth(index).locator(".ant-collapse-header").click();
  assert.equal(await page.locator(`#data_fields_${index}_key`).inputValue(), field.key, "field key visible after page reload");
  assert.equal(await page.locator(`#data_fields_${index}_label`).inputValue(), field.label, "field label visible after page reload");
  return field;
}

async function openProfileValues(page, fieldKey) {
  await page.goto("/dashboard/home", { waitUntil: "domcontentloaded" });
  await page.locator(".pro-account-trigger").click();
  await page.getByRole("menuitem", { name: "个人资料", exact: true }).click();
  await page.getByRole("dialog").filter({ has: page.getByRole("button", { name: "自定义资料", exact: true }) }).waitFor();
  await page.getByRole("button", { name: "自定义资料", exact: true }).click();
  await page.locator(`#${fieldKey}`).waitFor();
}

async function chooseVisibleSelectOption(page, label) {
  const dropdown = page.locator(".ant-select-dropdown:not(.ant-select-dropdown-hidden)");
  const option = dropdown.locator(".ant-select-item-option-content").getByText(label, { exact: true });
  await option.waitFor({ state: "visible" });
  await option.click();
}

async function ensureFeedbackCreatedThroughUi(session, title, detailHtml) {
  stage = `submit feedback ${title} through UI`;
  let list = await getJson(session.context, "/feedback?page=1&pageSize=100");
  let item = list.items.find(entry => entry.title === title);
  if (!item) {
    await session.page.goto("/dashboard/feedback", { waitUntil: "domcontentloaded" });
    await session.page.getByRole("button", { name: "提交反馈", exact: true }).click();
    const form = session.page.locator("form#feedback-create");
    await form.waitFor();
    await form.locator("#title").fill(title);
    await form.locator('[contenteditable="true"]').last().fill(detailHtml);
    const created = session.page.waitForResponse(response =>
      new URL(response.url()).pathname === "/api/feedback" && response.request().method() === "POST",
    );
    await session.page.locator('button[form="feedback-create"]').click();
    const response = await created;
    assert.equal(response.status(), 201, `feedback ${title} submitted through UI`);
    await form.waitFor({ state: "hidden" });
    list = await getJson(session.context, "/feedback?page=1&pageSize=100");
    item = list.items.find(entry => entry.title === title);
    assert.ok(item, `feedback ${title} appears in list after submit`);
    note(`feedback submitted through UI: ${title}`, { feedbackId: item.id, responseStatus: response.status() });
  }
  assert.ok(item, `feedback ${title} exists`);
  return item;
}

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
let adminContext;
let adminPage;
try {
  stage = "bootstrap UI login";
  ({ context: adminContext, page: adminPage } = await loginThroughUi(browser, privateConfig.adminUsername, privateConfig.adminPassword));
  if (!hasCheck("bootstrap login through normal UI")) note("bootstrap login through normal UI", { frontendOrigin: origin });
  const roles = [];
  const existingBase = results.roles?.find(role => role.code === roleCodes.base);
  if (existingBase?.id) roles.push(await getJson(adminContext, `/platform/roles/${existingBase.id}`));
  else roles.push(await createRole(adminPage, {
    code: roleCodes.base,
    name: `QA ordinary base ${tag.slice(0, 8)}`,
    includeUserRead: true,
    includeFeedback: true,
  }));
  results.roles = roles.map(({ id, code, enabled, dataScope, fileQuotaBytesLimit, fileQuotaFilesLimit, authorities }) => ({ id, code, enabled, dataScope, fileQuotaBytesLimit, fileQuotaFilesLimit, authorities }));
  if (!hasCheck("base SELF role created through UI")) note("base SELF role created through UI", { authorityCount: roles[0].authorities.length, dataScope: roles[0].dataScope });

  const listedUsers = await getJson(adminContext, "/platform/users?page=1&pageSize=100");
  const ordinaryUser = results.users?.ordinary?.id
    ? listedUsers.items.find(item => item.id === results.users.ordinary.id)
    : await createUser(adminPage, ordinary);
  const peerUser = results.users?.peer?.id
    ? listedUsers.items.find(item => item.id === results.users.peer.id)
    : await createUser(adminPage, peer);
  results.users = {
    ordinary: { id: ordinaryUser.id, username: ordinaryUser.username },
    peer: { id: peerUser.id, username: peerUser.username },
  };
  saveResults();
  savePrivateFixture({ ordinaryUserId: ordinaryUser.id, peerUserId: peerUser.id, marker });
  if (!hasCheck("unique ordinary and peer QA users created through UI")) note("unique ordinary and peer QA users created through UI", { ordinaryUserId: ordinaryUser.id, peerUserId: peerUser.id });

  const assignedOrdinary = await setUserRoleCodes(adminPage, ordinary.username, roles, [roleCodes.base]);
  if (!hasCheck("ordinary SELF/feedback role assigned through UI")) note("ordinary SELF/feedback role assigned through UI", { userId: ordinaryUser.id, roleCount: assignedOrdinary.roleIds.length });

  const ordinarySession = await loginThroughUi(browser, ordinary.username, ordinary.password);
  if (!hasCheck("SELF isolation and localization management denial")) {
    const ordinaryUsers = await getJson(ordinarySession.context, "/platform/users?page=1&pageSize=100");
    const visibleUsernames = ordinaryUsers.items.map(item => item.username);
    assert.ok(visibleUsernames.includes(ordinary.username), "SELF list contains the current user");
    assert.equal(visibleUsernames.includes(peer.username), false, "SELF list hides peer");
    assert.equal(visibleUsernames.includes(privateConfig.adminUsername), false, "SELF list hides administrator");
    const peerDetailResponse = await ordinarySession.context.request.get(apiUrl(`/platform/users/${peerUser.id}`));
    assert.ok([403, 404].includes(peerDetailResponse.status()), "SELF cannot read peer detail");
    const roleListResponse = await ordinarySession.context.request.get(apiUrl("/platform/roles?page=1&pageSize=20"));
    assert.equal(roleListResponse.status(), 403, "ordinary user cannot list role management data");
    const localizationResponse = await ordinarySession.context.request.get(apiUrl("/platform/localization/locales"));
    assert.equal(localizationResponse.status(), 403, "ordinary user cannot read localization management");
    await ordinarySession.page.goto("/dashboard/localization", { waitUntil: "domcontentloaded" });
    await ordinarySession.page.getByText("403", { exact: true }).waitFor();
    assert.equal(await ordinarySession.page.getByRole("button", { name: "AI 翻译", exact: true }).count(), 0, "localization write controls are absent");
    assert.equal(await ordinarySession.page.locator('[aria-label="语言"]').count(), 0, "localization editor is not usable");
    note("SELF isolation and localization management denial", {
      visibleUserCount: visibleUsernames.length,
      selfListed: visibleUsernames.includes(ordinary.username),
      peerDetailStatus: peerDetailResponse.status(),
      roleListStatus: roleListResponse.status(),
      localizationStatus: localizationResponse.status(),
      roleCount: assignedOrdinary.roleIds.length,
    });
  }

  const peerSession = await loginThroughUi(browser, peer.username, peer.password);
  if (!hasCheck("403 feedback denial and recovery after UI role assignment")) {
    const deniedFeedback = await peerSession.context.request.get(apiUrl("/feedback/options"));
    assert.equal(deniedFeedback.status(), 403, "unassigned peer lacks feedback access");
    await peerSession.page.goto("/dashboard/feedback", { waitUntil: "domcontentloaded" });
    await peerSession.page.waitForTimeout(500);
    const peerAccessBefore = {
      createButtonCount: await peerSession.page.getByRole("button", { name: "提交反馈", exact: true }).count(),
      deniedStatus: deniedFeedback.status(),
    };
    assert.equal(peerAccessBefore.createButtonCount, 0, "feedback create action absent before role assignment");
    const assignedPeer = await setUserRoleCodes(adminPage, peer.username, roles, [roleCodes.base]);
    const recoveredFeedback = await peerSession.context.request.get(apiUrl("/feedback/options"));
    assert.equal(recoveredFeedback.status(), 200, "peer feedback access recovers after UI role assignment");
    await peerSession.page.goto("/dashboard/feedback", { waitUntil: "domcontentloaded" });
    await peerSession.page.getByRole("button", { name: "提交反馈", exact: true }).waitFor();
    note("403 feedback denial and recovery after UI role assignment", {
      before: peerAccessBefore,
      afterStatus: recoveredFeedback.status(),
      assignedRoleCount: assignedPeer.roleIds.length,
    });
  }

  const storageRole = await ensureRole(adminPage, roles, roleCodes.storage, `QA storage ${tag.slice(0, 8)}`, { storageGiB: 0.002 });
  const countRole = await ensureRole(adminPage, roles, roleCodes.count, `QA count ${tag.slice(0, 8)}`, { fileCount: 3 });
  const stricterRole = await ensureRole(adminPage, roles, roleCodes.stricter, `QA strict ${tag.slice(0, 8)}`, { storageGiB: 0.001, fileCount: 5 });
  const storageLimit = Math.round(0.002 * 1024 ** 3);
  const strictLimit = Math.round(0.001 * 1024 ** 3);
  const defaultBytes = 81920;
  const defaultFiles = 1000;

  if (!hasQuotaCheck("storage-only")) {
    const state = await setUserRoleCodes(adminPage, ordinary.username, roles, [roleCodes.base, roleCodes.storage]);
    await assertEffectiveQuota(ordinarySession.context, adminPage, "storage-only", storageLimit, defaultFiles, state.dialogText);
  }
  if (!hasQuotaCheck("count-only")) {
    const state = await setUserRoleCodes(adminPage, ordinary.username, roles, [roleCodes.base, roleCodes.count]);
    await assertEffectiveQuota(ordinarySession.context, adminPage, "count-only", defaultBytes, 3, state.dialogText);
  }
  if (!hasQuotaCheck("storage-and-count")) {
    const state = await setUserRoleCodes(adminPage, ordinary.username, roles, [roleCodes.base, roleCodes.storage, roleCodes.count]);
    await assertEffectiveQuota(ordinarySession.context, adminPage, "storage-and-count", storageLimit, 3, state.dialogText);
  }
  if (!hasQuotaCheck("strict-storage-and-three-file-minimum")) {
    const state = await setUserRoleCodes(adminPage, ordinary.username, roles, [roleCodes.base, roleCodes.storage, roleCodes.count, roleCodes.stricter]);
    await assertEffectiveQuota(ordinarySession.context, adminPage, "strict-storage-and-three-file-minimum", strictLimit, 3, state.dialogText);
  }
  if (!hasQuotaCheck("disabled-strict-role-ignored")) {
    await setRoleEnabled(adminPage, stricterRole.id, roleCodes.stricter, false);
    await assertEffectiveQuota(ordinarySession.context, adminPage, "disabled-strict-role-ignored", storageLimit, 3);
  }
  if (!hasQuotaCheck("unassigned-strict-role-ignored")) {
    await setRoleEnabled(adminPage, stricterRole.id, roleCodes.stricter, true);
    const state = await setUserRoleCodes(adminPage, ordinary.username, roles, [roleCodes.base, roleCodes.storage, roleCodes.count]);
    await assertEffectiveQuota(ordinarySession.context, adminPage, "unassigned-strict-role-ignored", storageLimit, 3, state.dialogText);
  }
  if (!hasQuotaCheck("no-role-limits-defaults")) {
    const state = await setUserRoleCodes(adminPage, ordinary.username, roles, [roleCodes.base]);
    await assertEffectiveQuota(ordinarySession.context, adminPage, "no-role-limits-defaults", defaultBytes, defaultFiles, state.dialogText);
  }
  results.quotaRoles = [storageRole, countRole, stricterRole].map(({ id, code, fileQuotaBytesLimit, fileQuotaFilesLimit }) => ({ id, code, fileQuotaBytesLimit, fileQuotaFilesLimit }));
  saveResults();

  if (!hasCheck("80KiB upload accepted; 80KiB+1 rejected; SHA download and refresh persistence")) {
    stage = "ordinary file quota boundary, download and refresh";
    await ordinarySession.page.goto("/dashboard/files", { waitUntil: "domcontentloaded" });
    await ordinarySession.page.getByRole("region", { name: "文件用量", exact: true }).waitFor();
    const acceptedName = `${marker}-81920.txt`;
    const acceptedBody = Buffer.alloc(81920, 0x61);
    const expectedSha = createHash("sha256").update(acceptedBody).digest("hex");
    let files = await getJson(ordinarySession.context, "/account/files?page=1&pageSize=100");
    let accepted = files.items.find(file => file.originalName === acceptedName);
    if (!accepted) {
      const uploaded = ordinarySession.page.waitForResponse(response =>
        new URL(response.url()).pathname === "/api/account/files" && response.request().method() === "POST",
      );
      await ordinarySession.page.locator('input[type="file"]').setInputFiles({ name: acceptedName, mimeType: "text/plain", buffer: acceptedBody });
      const response = await uploaded;
      assert.equal(response.status(), 201, "81920-byte upload accepted");
      accepted = await response.json();
    }
    const acceptedRow = ordinarySession.page.locator(".ant-table-row").filter({ hasText: acceptedName });
    await acceptedRow.first().waitFor();
    const afterUpload = await getJson(ordinarySession.context, "/account/files/usage");
    assert.equal(afterUpload.chargedBytes, 81920, "accepted file charged exactly 81920 bytes");
    const downloadStarted = ordinarySession.page.waitForEvent("download");
    await acceptedRow.first().getByRole("button", { name: "下载", exact: true }).click();
    await ordinarySession.page.locator(".ant-popconfirm").getByRole("button", { name: /下\s*载/ }).click();
    const download = await downloadStarted;
    assert.equal(await download.failure(), null, "download completed");
    const downloadedBytes = readFileSync(await download.path());
    const downloadedSha = createHash("sha256").update(downloadedBytes).digest("hex");
    assert.equal(downloadedBytes.length, 81920, "downloaded file length");
    assert.equal(downloadedSha, expectedSha, "downloaded file SHA-256");
    const downloadEvidence = await ordinarySession.context.request.get(apiUrl(`/account/files/${accepted.id}/content`));
    assert.equal(downloadEvidence.status(), 200, "read-only content response check");
    assert.equal(createHash("sha256").update(await downloadEvidence.body()).digest("hex"), downloadedSha, "read-only content bytes match browser download");
    await ordinarySession.page.reload({ waitUntil: "domcontentloaded" });
    await ordinarySession.page.locator(".ant-table-row").filter({ hasText: acceptedName }).first().waitFor();
    files = await getJson(ordinarySession.context, "/account/files?page=1&pageSize=100");
    accepted = files.items.find(file => file.originalName === acceptedName);
    assert.ok(accepted, "accepted file persists after browser refresh");
    const afterRefresh = await getJson(ordinarySession.context, "/account/files/usage");
    assert.equal(afterRefresh.chargedBytes, 81920, "usage persists after browser refresh");
    assert.equal(afterRefresh.maximumBytes, 81920, "80KiB effective system quota");
    const usageUi = await ordinarySession.page.getByRole("region", { name: "文件用量", exact: true }).innerText();
    assert.match(usageUi, /80 KiB\s*\/\s*80 KiB/, "80 KiB capacity appears in UI");

    const rejectedName = `${marker}-81921.txt`;
    const oversizedBody = Buffer.alloc(81921, 0x62);
    const rejected = ordinarySession.page.waitForResponse(response =>
      new URL(response.url()).pathname === "/api/account/files" && response.request().method() === "POST",
    );
    await ordinarySession.page.locator('input[type="file"]').setInputFiles({ name: rejectedName, mimeType: "text/plain", buffer: oversizedBody });
    const rejectedResponse = await rejected;
    assert.equal(rejectedResponse.status(), 409, "81921-byte upload rejected by quota");
    await ordinarySession.page.getByText("文件容量或数量已达上限，请清理文件后重试", { exact: true }).waitFor();
    const afterReject = await getJson(ordinarySession.context, "/account/files/usage");
    assert.equal(afterReject.chargedBytes, 81920, "rejected file does not increase usage");
    assert.equal(afterReject.fileCount, 1, "rejected file does not increase count");
    note("80KiB upload accepted; 80KiB+1 rejected; SHA download and refresh persistence", {
      acceptedBytes: downloadedBytes.length,
      sha256Verified: downloadedSha === expectedSha,
      downloadStatus: downloadEvidence.status(),
      rejectedStatus: rejectedResponse.status(),
      chargedBytesAfterRefresh: afterRefresh.chargedBytes,
      chargedBytesAfterReject: afterReject.chargedBytes,
    });
  }

  const { dictionary, item: dictionaryItem } = await ensureDictionaryAndItem(adminPage);
  results.dictionary = { id: dictionary.id, code: dictionary.code, name: dictionary.name };
  results.dictionaryItem = { id: dictionaryItem.id, value: dictionaryItem.value, label: dictionaryItem.label };
  saveResults();
  savePrivateFixture({
    marker,
    roleCodes,
    dictionaryCode: dictionary.code,
    dictionaryName: dictionary.name,
    dictionaryId: dictionary.id,
    dictionaryItemId: dictionaryItem.id,
    dictionaryItemValue: dictionaryItem.value,
    dictionaryItemLabel: dictionaryItem.label,
  });
  const selectField = await ensureSelectField(adminPage, dictionary);
  if (!hasCheck("SELECT dictionary option and profile field created through UI")) {
    note("SELECT dictionary option and profile field created through UI", {
      dictionaryCode: dictionary.code,
      dictionaryId: dictionary.id,
      itemId: dictionaryItem.id,
      fieldKey: selectField.key,
      fieldLabel: selectField.label,
    });
  }

  if (!hasCheck("SELECT account value saved and survives browser refresh")) {
    stage = "ordinary SELECT profile value selection/save/reload";
    await openProfileValues(ordinarySession.page, selectField.key);
    const fieldControl = ordinarySession.page.locator(`#${selectField.key}`);
    await fieldControl.click();
    await chooseVisibleSelectOption(ordinarySession.page, dictionaryItem.label);
    const saved = ordinarySession.page.waitForResponse(response =>
      new URL(response.url()).pathname === "/api/account/profile-values" && response.request().method() === "PUT",
    );
    await ordinarySession.page.locator('button[form="account-profile-values"]').click();
    assert.equal((await saved).status(), 200, "SELECT profile value save");
    await ordinarySession.page.reload({ waitUntil: "domcontentloaded" });
    await openProfileValues(ordinarySession.page, selectField.key);
    const selectContainer = ordinarySession.page.locator(`.ant-select:has(#${selectField.key})`);
    const selectedLabel = await selectContainer.locator(".ant-select-content").getAttribute("title");
    assert.equal(selectedLabel, dictionaryItem.label, "selected option label after refresh");
    const profileValues = await getJson(ordinarySession.context, "/account/profile-values");
    assert.equal(profileValues.values[selectField.key], dictionaryItem.id, "persisted profile value is dictionary item ID");
    note("SELECT account value saved and survives browser refresh", {
      fieldKey: selectField.key,
      selectedLabel,
      persistedItemId: profileValues.values[selectField.key],
    });
  }

  if (!hasCheck("SELECT account value cleared and survives browser refresh")) {
    stage = "ordinary SELECT profile value clear/save/reload";
    let profileValues = await getJson(ordinarySession.context, "/account/profile-values");
    if (profileValues.values[selectField.key] !== dictionaryItem.id) {
      await openProfileValues(ordinarySession.page, selectField.key);
      const fieldControl = ordinarySession.page.locator(`#${selectField.key}`);
      await fieldControl.click();
      await chooseVisibleSelectOption(ordinarySession.page, dictionaryItem.label);
      const saved = ordinarySession.page.waitForResponse(response =>
        new URL(response.url()).pathname === "/api/account/profile-values" && response.request().method() === "PUT",
      );
      await ordinarySession.page.locator('button[form="account-profile-values"]').click();
      assert.equal((await saved).status(), 200, "restore SELECT value through account UI before clear check");
    }
    await ordinarySession.page.reload({ waitUntil: "domcontentloaded" });
    await openProfileValues(ordinarySession.page, selectField.key);
    const fieldControl = ordinarySession.page.locator(`#${selectField.key}`);
    const selectContainer = ordinarySession.page.locator(`.ant-select:has(#${selectField.key})`);
    const clearButton = selectContainer.locator(".ant-select-clear");
    await fieldControl.hover();
    await clearButton.waitFor({ state: "visible" });
    await clearButton.click();
    const cleared = ordinarySession.page.waitForResponse(response =>
      new URL(response.url()).pathname === "/api/account/profile-values" && response.request().method() === "PUT",
    );
    await ordinarySession.page.locator('button[form="account-profile-values"]').click();
    assert.equal((await cleared).status(), 200, "SELECT profile value clear");
    await ordinarySession.page.reload({ waitUntil: "domcontentloaded" });
    await openProfileValues(ordinarySession.page, selectField.key);
    assert.equal(await ordinarySession.page.locator(`.ant-select:has(#${selectField.key}) .ant-select-content-has-value`).count(), 0, "empty selection visible after refresh");
    profileValues = await getJson(ordinarySession.context, "/account/profile-values");
    const clearedValue = profileValues.values[selectField.key];
    assert.ok(clearedValue === undefined || clearedValue === null || clearedValue === "", "cleared profile value is absent or empty in persisted snapshot");
    note("SELECT account value cleared and survives browser refresh", {
      fieldKey: selectField.key,
      persistedValueEmpty: clearedValue === undefined || clearedValue === null || clearedValue === "",
      apiValueOmitted: !Object.hasOwn(profileValues.values, selectField.key),
    });
  }

  const ordinaryFeedbackTitle = `${marker} owner feedback`;
  const peerFeedbackTitle = `${marker} peer feedback`;
  const ordinaryFeedback = await ensureFeedbackCreatedThroughUi(
    ordinarySession,
    ordinaryFeedbackTitle,
    `Synthetic ordinary QA feedback ${marker}.`,
  );
  const peerFeedback = await ensureFeedbackCreatedThroughUi(
    peerSession,
    peerFeedbackTitle,
    `Synthetic peer QA feedback ${marker}.`,
  );

  if (!hasCheck("feedback owner-only list and cross-owner detail denial")) {
    stage = "verify feedback owner-only visibility";
    await ordinarySession.page.goto("/dashboard/feedback", { waitUntil: "domcontentloaded" });
    const ordinaryRow = ordinarySession.page.locator(".ant-table-row").filter({ hasText: ordinaryFeedbackTitle });
    await ordinaryRow.first().waitFor();
    assert.equal(await ordinarySession.page.locator(".ant-table-row").filter({ hasText: peerFeedbackTitle }).count(), 0, "ordinary UI hides peer feedback");
    const ordinaryList = await getJson(ordinarySession.context, "/feedback?page=1&pageSize=100");
    assert.ok(ordinaryList.items.some(entry => entry.id === ordinaryFeedback.id), "ordinary API list includes own feedback");
    assert.equal(ordinaryList.items.some(entry => entry.id === peerFeedback.id), false, "ordinary API list excludes peer feedback");
    const crossOwnerDetail = await ordinarySession.context.request.get(apiUrl(`/feedback/${peerFeedback.id}`));
    assert.ok([403, 404].includes(crossOwnerDetail.status()), "ordinary cannot read peer feedback detail");

    await peerSession.page.goto("/dashboard/feedback", { waitUntil: "domcontentloaded" });
    await peerSession.page.locator(".ant-table-row").filter({ hasText: peerFeedbackTitle }).first().waitFor();
    assert.equal(await peerSession.page.locator(".ant-table-row").filter({ hasText: ordinaryFeedbackTitle }).count(), 0, "peer UI hides ordinary feedback");
    const peerList = await getJson(peerSession.context, "/feedback?page=1&pageSize=100");
    assert.ok(peerList.items.some(entry => entry.id === peerFeedback.id), "peer API list includes own feedback");
    assert.equal(peerList.items.some(entry => entry.id === ordinaryFeedback.id), false, "peer API list excludes ordinary feedback");
    note("feedback owner-only list and cross-owner detail denial", {
      ordinaryOwnVisible: true,
      peerOwnVisible: true,
      peerHiddenFromOrdinary: true,
      ordinaryHiddenFromPeer: true,
      crossOwnerDetailStatus: crossOwnerDetail.status(),
    });
  }

  const adminFeedbackList = await getJson(adminContext, "/feedback/manage?page=1&pageSize=100");
  const managedFeedback = adminFeedbackList.items.find(entry => entry.id === ordinaryFeedback.id);
  assert.ok(managedFeedback, "admin feedback management list includes ordinary submission");
  const desiredAdminReply = `QA administrator reply ${tag.slice(0, 8)}`;
  let managedDetail = await getJson(adminContext, `/feedback/manage/${ordinaryFeedback.id}`);
  if (managedDetail.status !== "RESOLVED" || managedDetail.adminReply !== desiredAdminReply) {
    stage = "administrator feedback processing through UI";
    await adminPage.goto("/dashboard/feedback", { waitUntil: "domcontentloaded" });
    const row = adminPage.locator(".ant-table-row").filter({ hasText: ordinaryFeedbackTitle });
    await row.first().waitFor();
    await row.first().getByRole("button", { name: "详情", exact: true }).click();
    await adminPage.getByRole("button", { name: "处理反馈", exact: true }).waitFor();
    await adminPage.getByRole("button", { name: "处理反馈", exact: true }).click();
    const form = adminPage.locator("form#feedback-handle");
    await form.waitFor();
    await form.locator(".ant-select").first().click();
    await chooseVisibleSelectOption(adminPage, "已解决");
    await form.locator("textarea").fill(desiredAdminReply);
    const updated = adminPage.waitForResponse(response =>
      new URL(response.url()).pathname === `/api/feedback/manage/${ordinaryFeedback.id}` && response.request().method() === "PUT",
    );
    await adminPage.locator('button[form="feedback-handle"]').click();
    const response = await updated;
    assert.equal(response.status(), 200, "admin feedback processing UI save");
    await form.waitFor({ state: "hidden" });
  }
  managedDetail = await getJson(adminContext, `/feedback/manage/${ordinaryFeedback.id}`);
  assert.equal(managedDetail.status, "RESOLVED", "admin selected resolved feedback status");
  assert.equal(managedDetail.adminReply, desiredAdminReply, "admin reply persisted");
  if (!hasCheck("administrator processed feedback through UI")) {
    note("administrator processed feedback through UI", {
      feedbackId: ordinaryFeedback.id,
      status: managedDetail.status,
      replyPersisted: managedDetail.adminReply === desiredAdminReply,
    });
  }

  if (!hasCheck("admin processes ordinary feedback and ordinary sees reply after refresh")) {
    stage = "verify ordinary sees administrator feedback response after refresh";
    await ordinarySession.page.goto("/dashboard/feedback", { waitUntil: "domcontentloaded" });
    await ordinarySession.page.reload({ waitUntil: "domcontentloaded" });
    const ordinaryRow = ordinarySession.page.locator(".ant-table-row").filter({ hasText: ordinaryFeedbackTitle });
    await ordinaryRow.first().waitFor();
    await ordinaryRow.first().getByRole("button", { name: "详情", exact: true }).click();
    const detailDrawer = ordinarySession.page.getByLabel("反馈详情");
    await detailDrawer.getByText(desiredAdminReply, { exact: true }).waitFor();
    await detailDrawer.getByText("已解决", { exact: true }).waitFor();
    const ordinaryDetail = await getJson(ordinarySession.context, `/feedback/${ordinaryFeedback.id}`);
    assert.equal(ordinaryDetail.status, "RESOLVED", "ordinary detail status after refresh");
    assert.equal(ordinaryDetail.adminReply, desiredAdminReply, "ordinary detail reply after refresh");
    note("admin processes ordinary feedback and ordinary sees reply after refresh", {
      status: ordinaryDetail.status,
      replyVisibleInUi: true,
      replyPersisted: ordinaryDetail.adminReply === desiredAdminReply,
    });
  }

  results.status = "complete";
  delete results.next;
  delete results.failedAt;
  delete results.failureType;
  delete results.failure;
  saveResults();
  savePrivateFixture({ ordinaryUserId: ordinaryUser.id, peerUserId: peerUser.id, marker, roleCodes, qaRoleIds: roles.map(role => role.id) });
} catch (error) {
  failure = error;
  results.status = "failed";
  results.failedAt = stage;
  results.failureType = error?.constructor?.name ?? "Error";
  results.failure = redact(error?.message ?? String(error)).slice(0, 900);
  results.checks = [...new Map((results.checks ?? []).map(check => [check.name, check])).values()];
  saveResults();
  savePrivateFixture({ ordinaryUserId: results.users?.ordinary?.id, peerUserId: results.users?.peer?.id, marker, roleCodes });
  process.stdout.write(`FAIL ${stage}: ${results.failureType}\n`);
} finally {
  await adminContext?.close();
  await browser.close();
}
if (failure) process.exitCode = 1;
