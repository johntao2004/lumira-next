import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { PREFERENCES_COOKIE, parsePreferences, serializePreferences, defaultPreferences } from '../apps/web/src/platform/preferences/model.ts';

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010' });
try {
  const initial = { ...defaultPreferences, dark: true, locale: 'en' };
  await context.addCookies([{ name: PREFERENCES_COOKIE, value: serializePreferences(initial), url: 'http://127.0.0.1:8010' }]);
  const firstResponse = await context.request.get('/user/login');
  assert.equal(firstResponse.status(), 200);
  const firstHtml = await firstResponse.text();
  assert.match(firstHtml, /lumira-next\.preferences=/);
  assert.ok(
    firstHtml.indexOf('lumira-next.preferences=') < firstHtml.indexOf('<body>'),
    'preference bootstrap must execute in the document head before the app body',
  );
  const page = await context.newPage();
  await page.goto('/user/login');
  await expect.poll(() => page.evaluate(() => ({ language: document.documentElement.lang, theme: document.documentElement.dataset.theme }))).toEqual({ language: 'en', theme: 'dark' });
  await expect(page.getByRole('button', { name: 'Choose language', exact: true })).toHaveText('English');
  await expect(page.getByRole('button', { name: 'Switch to light mode', exact: true })).toBeVisible();

  const second = await context.newPage();
  await second.goto('/user/login');
  await page.getByRole('button', { name: 'Choose language', exact: true }).click();
  await page.getByRole('menuitem', { name: '简体中文', exact: true }).click();
  await expect.poll(() => second.evaluate(() => document.documentElement.lang)).toBe('zh-CN');
  await expect(second.getByRole('button', { name: '选择语言', exact: true })).toHaveText('简体中文');
  await page.getByRole('button', { name: '切换为日间模式', exact: true }).click();
  await expect.poll(() => second.evaluate(() => document.documentElement.dataset.theme)).toBe('light');

  const legacy = await browser.newContext({ baseURL: 'http://127.0.0.1:8010' });
  const legacyPage = await legacy.newPage();
  await legacyPage.addInitScript(() => {
    localStorage.setItem('lumira-next.theme', 'dark');
    localStorage.setItem('lumira-next.locale', 'de-DE');
    localStorage.setItem('lumira-next.navigation', 'dual');
    localStorage.setItem('lumira-next.rail', JSON.stringify({ labels: false, pinned: false }));
  });
  await legacyPage.goto('/user/login');
  await expect.poll(() => legacyPage.evaluate(() => ({
    language: document.documentElement.lang,
    theme: document.documentElement.dataset.theme,
  }))).toEqual({ language: 'de', theme: 'dark' });
  await expect(legacyPage.getByRole('button', { name: 'Sprache auswählen', exact: true })).toHaveText('Deutsch');
  await expect.poll(async () => {
    const cookie = (await legacy.cookies()).find(item => item.name === PREFERENCES_COOKIE);
    return cookie ? parsePreferences(cookie.value) : null;
  }).toEqual({ dark: true, locale: 'de' });
  await legacy.close();
  await page.close();
  await second.close();
  console.log('PASS: the static document bootstraps theme/locale in the head, cross-tab sync works, and retired navigation preferences are ignored');
} finally {
  await context.close();
  await browser.close();
}
