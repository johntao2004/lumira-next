import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, existsSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

// A normal Playwright context enables focus emulation and keeps background tabs
// visible. Attach to our own native profile without overrides to test real hide.
const baseURL = process.env.LUMIRA_QA_URL ?? 'http://127.0.0.1:8010';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), 'Local QA only');
const config = JSON.parse(readFileSync(process.env.LUMIRA_QA_CONFIG ?? '.runtime/local.json', 'utf8'));
const evidenceDir = resolve(process.env.LUMIRA_QA_EVIDENCE ?? '.runtime/monitoring-visibility');
mkdirSync(evidenceDir, { recursive: true });
const profile = mkdtempSync(join(tmpdir(), 'lumira-monitor-native-'));
const child = spawn(process.env.BROWSER_EXECUTABLE ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
  `--user-data-dir=${profile}`, '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
  '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--window-size=1200,850', 'about:blank',
], { stdio: 'ignore' });
const evidence = { mode: 'native default context, no viewport/focus overrides', requests: [], samples: [], pageErrors: [] };
const save = () => writeFileSync(join(evidenceDir, 'result.json'), JSON.stringify(evidence, null, 2));
let browser;
try {
  for (let i = 0; i < 100 && !existsSync(join(profile, 'DevToolsActivePort')); i++) await new Promise(r => setTimeout(r, 100));
  const port = readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0];
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, { noDefaults: true });
  const context = browser.contexts()[0];
  const page = context.pages()[0] ?? await context.newPage();
  page.on('pageerror', error => evidence.pageErrors.push(error.message));
  page.on('request', request => { if (request.url().endsWith('/api/platform/monitoring/summary')) evidence.requests.push({ time: Date.now() }); });
  await page.goto(`${baseURL}/user/login`);
  await page.getByLabel('用户名', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill(config.LUMIRA_BOOTSTRAP_PASSWORD);
  await page.getByRole('button', { name: /^登\s*录$/ }).click();
  await page.waitForURL('**/dashboard/**');
  await page.goto(`${baseURL}/dashboard/monitoring`);
  await expect(page.getByRole('heading', { name: '运行监控', exact: true })).toBeVisible();
  evidence.initial = await page.evaluate(() => ({ viewport: [innerWidth, innerHeight], visibility: document.visibilityState }));
  const other = await context.newPage();
  await other.goto('about:blank');
  await other.bringToFront();
  await page.waitForFunction(() => document.visibilityState === 'hidden');
  evidence.hiddenStart = Date.now();
  for (let i = 0; i < 15; i++) {
    evidence.samples.push({ time: Date.now(), ...await page.evaluate(() => ({ visibility: document.visibilityState, focus: document.hasFocus() })) });
    await page.waitForTimeout(1000);
  }
  evidence.hiddenEnd = Date.now();
  assert.ok(evidence.samples.every(sample => sample.visibility === 'hidden'));
  assert.equal(evidence.requests.filter(r => r.time >= evidence.hiddenStart && r.time <= evidence.hiddenEnd).length, 0, 'Hidden tab must not poll');
  await page.bringToFront();
  await page.waitForFunction(() => document.visibilityState === 'visible');
  evidence.resumeStart = Date.now();
  await page.waitForTimeout(11500);
  evidence.resumeEnd = Date.now();
  const resumed = evidence.requests.filter(r => r.time >= evidence.resumeStart);
  assert.ok(resumed.length >= 2, 'Visible tab must resume five-second polling');
  assert.deepEqual(evidence.pageErrors, []);
  await page.screenshot({ path: join(evidenceDir, 'returned.png') });
  evidence.result = 'PASS';
  save();
  console.log('PASS: native hidden tab pauses polling for 15 seconds and resumes after visible; no page errors');
} catch (error) {
  evidence.result = 'FAIL';
  evidence.failure = error.message;
  save();
  throw error;
} finally {
  if (browser) {
    try {
      const context = browser.contexts()[0];
      const token = await (await context.request.get(`${baseURL}/api/auth/csrf`)).json();
      evidence.cleanupLogoutStatus = (await context.request.post(`${baseURL}/api/auth/logout`, { headers: { [token.headerName]: token.token } })).status();
    } catch { evidence.cleanupLogoutFailed = true; }
    save();
    await browser.close();
  }
  child.kill('SIGTERM');
  await new Promise(r => setTimeout(r, 500));
  rmSync(profile, { recursive: true, force: true });
}
