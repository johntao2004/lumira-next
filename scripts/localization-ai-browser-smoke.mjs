import {chromium, expect} from '@playwright/test';
import {existsSync, mkdirSync, readFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {assertQaRunGuard} from './qa-run-guard.mjs';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const configuredPath = process.env.LUMIRA_SMOKE_CONFIG;
const baseURL = process.env.BASE_URL;
if (!configuredPath || !baseURL) {
  throw new Error('Set LUMIRA_SMOKE_CONFIG to a disposable QA config and BASE_URL to its local web origin.');
}
const configPath = path.isAbsolute(configuredPath) ? configuredPath : path.resolve(projectRoot, configuredPath);
assert.notEqual(configPath, path.join(projectRoot, '.runtime', 'local.json'), 'Refusing to read the real local.json config');
assert.ok(existsSync(configPath), 'The explicitly configured QA config must exist');
const parsedBase = new URL(baseURL);
assert.ok(['127.0.0.1', 'localhost', '::1'].includes(parsedBase.hostname), 'Browser QA must target a local app');
const config = JSON.parse(readFileSync(configPath, 'utf8'));
assertQaRunGuard({projectRoot, configPath, config, baseURL});
const databaseUrl = new URL(String(config.LUMIRA_DB_URL ?? '').replace(/^jdbc:/, ''));
assert.ok(['127.0.0.1', 'localhost', '::1'].includes(databaseUrl.hostname), 'QA database must be local');
assert.match(decodeURIComponent(databaseUrl.pathname), /^\/lumira_(?:security|qa|test|smoke)_[a-z0-9_]+$/, 'Database name must use the dedicated Lumira QA namespace');
const password = config.LUMIRA_BOOTSTRAP_PASSWORD ?? config.bootstrapPassword ?? config.password;
assert.equal(typeof password, 'string');
assert.ok(password.length > 0, 'The isolated QA config must provide a bootstrap password');
const evidence = path.resolve(process.env.LUMIRA_SMOKE_EVIDENCE ?? path.join(projectRoot, '.runtime', 'localization-ai-qa'));
mkdirSync(evidence, {recursive: true});
const browser = await chromium.launch({headless: true});
try {
  const context = await browser.newContext({baseURL, viewport: {width: 1134, height: 869}});
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: {username: config.LUMIRA_SMOKE_USERNAME ?? config.LUMIRA_BOOTSTRAP_USERNAME ?? config.username ?? 'admin', password}, headers: {[csrf.headerName]: csrf.token},
  })).status(), 200);
  const plugins = await (await context.request.get('/api/platform/modules/plugins/frontend')).json();
  const localization = plugins.find(plugin => plugin.id === 'localization');
  assert.ok(localization, 'Localization plugin must be installed and enabled');
  assert.equal(localization.version, JSON.parse(readFileSync(path.join(projectRoot, 'plugins/localization/plugin.json'), 'utf8')).version);
  const assetPath = `/api/platform/modules/plugins/assets/${localization.digest}/plugin.js`;
  const assetResponse = await context.request.get(assetPath);
  assert.equal(assetResponse.status(), 200);
  const releaseAsset = await assetResponse.body();
  assert.deepEqual(releaseAsset, readFileSync(path.join(projectRoot, 'plugins/localization/dist/package/frontend/plugin.js')));
  const providerOptions = [
    {id: 'qa-provider-a', name: 'QA deterministic provider A', modelName: 'qa-model-a'},
    {id: 'qa-provider-b', name: 'QA deterministic provider B', modelName: 'qa-model-b'},
  ];
  const localeOptions = await (await context.request.get('/api/platform/localization/locales')).json();
  // Freeze this verified release during the test; concurrent lifecycle tests can
  // restart the host or unload plugins without changing the candidate under test.
  await context.route('**/api/platform/modules/plugins/frontend', route => route.fulfill({json: plugins}));
  await context.route(`**${assetPath}`, route => route.fulfill({contentType: 'text/javascript', body: releaseAsset}));
  await context.route('**/api/platform/localization/ai/providers', route => route.fulfill({json: providerOptions}));
  await context.route('**/api/platform/localization/locales', route => route.fulfill({json: localeOptions}));
  const preferences = async (dark, locale = 'zh-CN') => context.addCookies([{
    name: 'lumira-next.preferences', value: encodeURIComponent(JSON.stringify({dark, locale})), url: baseURL,
  }]);
  await preferences(true);
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  const open = async () => {
    await page.getByTestId('pro-table').getByRole('button', {name: 'AI 翻译', exact: true}).click();
    const dialog = page.getByRole('dialog').filter({hasText: 'AI 翻译'});
    await dialog.locator('.localization-ai-fields').waitFor();
    await expect(dialog).toHaveCSS('opacity', '1');
    return dialog;
  };
  const close = async dialog => { await dialog.locator('.ant-modal-close').click(); await expect(dialog).not.toBeVisible(); };

  await page.goto('/dashboard/localization');
  let dialog = await open();
  await expect(dialog.getByRole('radio', {name: '所有未翻译的字段', exact: true})).toBeVisible();
  const boxes = await dialog.locator('.localization-ai-fields > label').evaluateAll(labels => labels.map(label => {
    const r = label.getBoundingClientRect(); return {x: r.x, y: r.y};
  }));
  assert.equal(boxes.length, 4);
  assert.ok(boxes[1].x > boxes[0].x && Math.abs(boxes[0].y - boxes[1].y) < 2);
  assert.ok(boxes[2].y > boxes[0].y && Math.abs(boxes[0].x - boxes[2].x) < 2);
  assert.ok(Math.abs(boxes[2].y - boxes[3].y) < 2 && Math.abs(boxes[1].x - boxes[3].x) < 2);
  await page.screenshot({path: `${evidence}/desktop-dark.png`, animations: 'disabled'});
  await close(dialog);
  await page.setViewportSize({width: 390, height: 844});
  dialog = await open();
  const columns = await dialog.locator('.localization-ai-fields').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length);
  assert.equal(columns, 1);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.screenshot({path: `${evidence}/mobile.png`, animations: 'disabled'});
  await close(dialog);
  await page.setViewportSize({width: 1134, height: 869});
  await preferences(false, 'en');
  await page.reload();
  await page.getByRole('button', {name: 'AI translation', exact: true}).click();
  await expect(page.getByRole('radio', {name: 'All untranslated fields', exact: true})).toBeVisible();
  await page.screenshot({path: `${evidence}/desktop-light-en.png`, animations: 'disabled'});
  await page.getByRole('dialog').locator('.ant-modal-close').click();
  await preferences(true);

  // Exercise the deployed plugin with deterministic data and model replies.
  // Every generation/save request is intercepted, so no provider is charged
  // and no business translations or locale releases are modified.
  const entries = Array.from({length: 105}, (_, i) => ({
    id: `qa-${i}`, messageKey: `custom.qa${String(i).padStart(3, '0')}`, namespace: 'custom',
    sourceMessage: `Source ${i}`, currentValue: '', valueSource: i % 2 ? 'FALLBACK' : 'MISSING',
    overridden: false, builtIn: false, enabled: true, version: 0, translations: {},
  }));
  entries.push(
    {...entries[0], id: 'manual', messageKey: 'custom.manual', overridden: true, currentValue: '人工译文'},
    {...entries[0], id: 'disabled', messageKey: 'custom.disabled', enabled: false},
    {...entries[0], id: 'static', messageKey: 'custom.static', valueSource: 'STATIC', currentValue: '已有译文'},
  );
  const entryRequests = [];
  const translationRequests = [];
  const saveRequests = [];
  const failedKey = entries[104].messageKey;
  let activeSnapshotToken = 'a'.repeat(64);
  let failedOnce = false;
  let saveFailedOnce = false;
  let sourceDriftRun = false;
  let driftTranslationCalls = 0;
  let releaseDriftResponse;
  let resolveDriftRequest;
  const driftRequestSeen = new Promise(resolve => { resolveDriftRequest = resolve; });
  const driftResponseGate = new Promise(resolve => { releaseDriftResponse = resolve; });
  await context.route('**/api/auth/csrf', route => route.fulfill({json: {headerName: 'X-CSRF-TOKEN', token: 'localization-test-token'}}));
  await context.route('**/api/platform/localization/entries?**', async route => {
    const params = new URL(route.request().url()).searchParams;
    entryRequests.push(Object.fromEntries(params));
    const current = Number(params.get('page'));
    const size = Number(params.get('pageSize'));
    await route.fulfill({json: {items: entries.slice((current - 1) * size, current * size), total: entries.length, page: current, pageSize: size}});
  });
  await context.route('**/api/public/localization/bundle/en', route => route.fulfill({json: {
    formatVersion: 1, locale: 'en', releaseVersion: 7, snapshotToken: activeSnapshotToken,
    messages: Object.fromEntries(entries.map(entry => [entry.messageKey, entry.sourceMessage])),
  }}));
  await context.route('**/api/platform/localization/ai/translate', async route => {
    const body = route.request().postDataJSON();
    translationRequests.push(body);
    if (sourceDriftRun && ++driftTranslationCalls === 2) {
      resolveDriftRequest(body);
      await driftResponseGate;
    }
    if (body.sourceSnapshotToken !== activeSnapshotToken) {
      await route.fulfill({status: 409, json: {message: 'localizationSourceChanged'}});
      return;
    }
    const items = body.messageKeys.map(messageKey => {
      if (messageKey === failedKey && !failedOnce) {failedOnce = true; return {messageKey, errorCode: 'localizationAiItemError'};}
      return {messageKey, translation: `译文 ${messageKey}`};
    });
    await route.fulfill({json: {items, succeeded: items.filter(item => item.translation).length, failed: items.filter(item => !item.translation).length}});
  });
  await context.route('**/api/platform/localization/translations', async route => {
    const body = route.request().postDataJSON();
    saveRequests.push(body);
    if (body.sourceSnapshotToken !== activeSnapshotToken) {
      await route.fulfill({status: 409, json: {message: 'localizationSourceChanged'}});
      return;
    }
    if (saveRequests.length === 2 && !saveFailedOnce) {
      saveFailedOnce = true;
      await route.fulfill({status: 503, json: {message: 'unavailable'}});
    } else await route.fulfill({json: {locale: body.locale, saved: body.entries.length, skipped: 0, releaseVersion: 1}});
  });
  await page.reload();
  await page.locator('.ant-pagination-item-2').click();
  await expect.poll(() => entryRequests.some(request => request.page === '2' && request.pageSize === '10')).toBe(true);
  dialog = await open();
  await dialog.getByRole('radio', {name: '所有未翻译的字段', exact: true}).check();
  await expect(dialog.getByRole('checkbox', {name: '仅翻译缺失项', exact: true})).toBeChecked();
  await expect(dialog.getByRole('checkbox', {name: '仅翻译缺失项', exact: true})).toBeDisabled();
  await expect(dialog.getByRole('checkbox', {name: '允许覆盖已有人工译文', exact: true})).not.toBeChecked();
  const start = entryRequests.length;
  await dialog.getByRole('button', {name: 'AI 翻译', exact: true}).click();
  await expect(dialog.getByText('已处理 105/105，失败 1', {exact: true})).toBeVisible({timeout: 30000});
  assert.deepEqual(entryRequests.slice(start).map(request => request.page), ['1', '2']);
  assert.ok(entryRequests.slice(start).every(request => request.pageSize === '100' && request.search === '' && !Object.keys(request).some(key => key.startsWith('filter.'))));
  assert.deepEqual(translationRequests.flatMap(request => request.messageKeys), entries.slice(0, 105).map(entry => entry.messageKey));
  assert.ok(translationRequests.every(request => request.messageKeys.length <= 5 && request.targetLocale === 'zh-CN' && request.sourceLocale === 'en' && request.sourceSnapshotToken === 'a'.repeat(64)));
  assert.equal(saveRequests.length, 0, 'AI preview must not save automatically');
  const retryStart = translationRequests.length;
  await dialog.getByRole('button', {name: '重试失败项', exact: true}).click();
  await expect(dialog.getByText('已处理 1/1，失败 0', {exact: true})).toBeVisible();
  assert.deepEqual(translationRequests.slice(retryStart).flatMap(request => request.messageKeys), [failedKey]);
  await dialog.getByRole('button', {name: '保存所选译文', exact: true}).click();
  await expect(dialog.locator('.ant-checkbox-group input')).toHaveCount(5);
  await expect(dialog.getByRole('button', {name: '保存所选译文', exact: true})).toHaveAttribute('aria-busy', 'false');
  assert.deepEqual(saveRequests.map(request => request.entries.length), [100, 5]);
  await dialog.getByRole('button', {name: '保存所选译文', exact: true}).click();
  await expect(dialog).not.toBeVisible();
  assert.deepEqual(saveRequests.map(request => request.entries.length), [100, 5, 5]);
  assert.ok(saveRequests.every(request => request.overwriteExisting === false && request.sourceLocale === 'en' && request.sourceSnapshotToken === 'a'.repeat(64)));

  dialog = await open();
  await dialog.getByRole('radio', {name: '当前页', exact: true}).check();
  const pageStart = translationRequests.length;
  await dialog.getByRole('button', {name: 'AI 翻译', exact: true}).click();
  await expect(dialog.getByText('已处理 10/10，失败 0', {exact: true})).toBeVisible();
  assert.deepEqual(translationRequests.slice(pageStart).flatMap(request => request.messageKeys), entries.slice(10, 20).map(entry => entry.messageKey));
  await close(dialog);
  for (const id of ['qa-10', 'qa-11']) {
    const checkbox = page.locator(`tr[data-row-key="${id}"] .ant-checkbox-input`);
    await checkbox.focus();
    await page.keyboard.press('Space');
    await expect(checkbox).toBeChecked();
  }
  dialog = await open();
  await expect(dialog.getByRole('radio', {name: '所选键 (2)', exact: true})).toBeVisible();
  await dialog.getByRole('radio', {name: '所选键 (2)', exact: true}).check();
  const selectedStart = translationRequests.length;
  await dialog.getByRole('button', {name: 'AI 翻译', exact: true}).click();
  await expect(dialog.getByText('已处理 2/2，失败 0', {exact: true})).toBeVisible();
  assert.deepEqual(translationRequests.slice(selectedStart).flatMap(request => request.messageKeys), entries.slice(10, 12).map(entry => entry.messageKey));

  await dialog.getByRole('combobox', {name: '提供方'}).click();
  await page.locator('.ant-select-item-option').filter({hasText: 'QA deterministic provider B'}).click();
  await expect(dialog.getByRole('heading', {name: 'AI 翻译预览'})).toHaveCount(0);
  await dialog.getByRole('radio', {name: '当前页', exact: true}).check();
  sourceDriftRun = true;
  driftTranslationCalls = 0;
  await dialog.getByRole('button', {name: 'AI 翻译', exact: true}).click();
  await expect.poll(() => driftTranslationCalls).toBe(2);
  const heldBatch = await driftRequestSeen;
  assert.equal(heldBatch.sourceSnapshotToken, activeSnapshotToken);
  await expect(dialog.getByText('译文 custom.qa010', {exact: true})).toBeVisible();
  activeSnapshotToken = 'b'.repeat(64);
  sourceDriftRun = false;
  releaseDriftResponse();
  await expect(dialog.getByRole('heading', {name: 'AI 翻译预览'})).toHaveCount(0);
  await expect(dialog.getByText('译文 custom.qa010', {exact: true})).toHaveCount(0);
  await expect(page.getByText('记录已被修改，请刷新后重试', {exact: true})).toBeVisible();
  assert.equal(saveRequests.length, 3, 'source changes during generation must discard the whole preview before save');

  const regeneratedStart = translationRequests.length;
  await dialog.getByRole('button', {name: 'AI 翻译', exact: true}).click();
  await expect(dialog.getByText('已处理 10/10，失败 0', {exact: true})).toBeVisible();
  assert.ok(translationRequests.slice(regeneratedStart).every(request => request.sourceSnapshotToken === 'b'.repeat(64)));
  assert.deepEqual(pageErrors, []);
  console.log('PASS: deployed localization plugin, two-by-two desktop selectors, mobile one column, Chinese/English labels, all untranslated pages and exclusions, bounded batches, cross-page retry, preview before save, partial-save retry, existing page/selection scopes, provider-scope invalidation, stale-source batch rejection/discard and regeneration; model and writes mocked');
} finally {
  await browser.close();
}
