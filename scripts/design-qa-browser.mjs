import assert from "node:assert/strict";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { chromium } from "@playwright/test";
import { PREFERENCES_COOKIE, serializePreferences } from "../apps/web/src/platform/preferences/model.ts";

const config = JSON.parse(readFileSync(".runtime/local.json", "utf8"));
const output = ".runtime/browser-checks/ant-pro-final";
mkdirSync(output, { recursive: true });

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const records = [];

async function auditPage(page, name, path, ready) {
  const pageErrors = [];
  const consoleErrors = [];
  const onPageError = error => pageErrors.push(error.message);
  const onConsole = message => {
    const location = message.location().url;
    const isMissingOptionalFavicon = location.endsWith("/favicon.ico") && message.text().includes("404");
    if (["error", "warning"].includes(message.type()) && !isMissingOptionalFavicon) consoleErrors.push(message.text());
  };
  page.on("pageerror", onPageError);
  page.on("console", onConsole);
  await page.goto(path, { waitUntil: "domcontentloaded" });
  await ready(page);
  await page.locator(".ant-spin-spinning").waitFor({ state: "detached", timeout: 2_000 }).catch(() => {});
  await page.waitForTimeout(350);
  const metrics = await page.evaluate(() => {
    const rects = selector => Array.from(document.querySelectorAll(selector)).map(node => node.getBoundingClientRect());
    const blocks = rects('.workbench > *');
    const cards = rects('.workbench-stats > *');
    const sider = document.querySelector('.ant-layout-sider');
    return {
      width: innerWidth,
      height: innerHeight,
      horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      theme: document.documentElement.dataset.theme,
      title: document.title,
      siderWidth: Math.round(sider?.getBoundingClientRect().width ?? 0),
      navigationGroupCount: document.querySelectorAll('.ant-pro-sider-menu > .ant-menu-submenu').length,
      retiredSidebarControlCount: document.querySelectorAll('[data-testid="navigation-mode-switch"], [data-testid="dual-label-toggle"], [data-testid="dual-pin-toggle"]').length,
      workbenchVerticalGaps: blocks.slice(1).map((rect, index) => Math.round(rect.top - blocks[index].bottom)),
      workbenchCardGaps: cards.slice(1).map((rect, index) => Math.round(rect.left - cards[index].right)),
    };
  });
  assert.equal(metrics.horizontalOverflow, false, `${name} has horizontal overflow`);
  if (name.startsWith('home-1440')) {
    assert.equal(metrics.siderWidth, 64, `${name} must default to the compact 64px ProLayout sider`);
    assert.equal(metrics.navigationGroupCount, 2, `${name} must expose both permission-filtered navigation groups`);
    assert.equal(metrics.retiredSidebarControlCount, 0, `${name} must not render retired dual-sidebar controls`);
    assert.deepEqual(metrics.workbenchVerticalGaps, [24, 24], `${name} must use 24px between page modules`);
    assert.deepEqual(metrics.workbenchCardGaps, [16, 16], `${name} must use 16px between related statistic cards`);
  }
  assert.deepEqual(pageErrors, [], `${name} emitted page errors`);
  assert.deepEqual(consoleErrors, [], `${name} emitted console errors`);
  const screenshot = `${output}/${name}.png`;
  await page.screenshot({ path: screenshot });
  records.push({ name, path, screenshot, ...metrics });
  page.off("pageerror", onPageError);
  page.off("console", onConsole);
}

try {
  const anonymous = await browser.newContext({ baseURL: "http://127.0.0.1:8010", viewport: { width: 1440, height: 1000 } });
  const login = await anonymous.newPage();
  await auditPage(login, "login-1440-light", "/user/login", page => page.locator(".login-content h1").waitFor());
  const desktopLogin = await login.evaluate(() => {
    const hero = document.querySelector(".login-hero")?.getBoundingClientRect();
    const shell = document.querySelector(".login-shell")?.getBoundingClientRect();
    return {
      heroWidth: Math.round(hero?.width ?? 0),
      shellWidth: Math.round(shell?.width ?? 0),
      headingCount: document.querySelectorAll(".login-content h1").length,
      pageHasBackground: document.querySelector(".login-page")?.getAttribute("data-has-background"),
      heroCount: document.querySelectorAll(".login-hero").length,
    };
  });
  assert.equal(desktopLogin.headingCount, 1);
  assert.equal(desktopLogin.pageHasBackground, "false", "default login must not claim a configured background");
  assert.equal(desktopLogin.heroCount, 0, "default login must not render the left visual panel");
  assert.ok(desktopLogin.shellWidth >= 400, "desktop login form panel must retain a usable width");
  await login.setViewportSize({ width: 390, height: 844 });
  await auditPage(login, "login-390-light", "/user/login", page => page.locator(".login-content h1").waitFor());
  assert.equal(await login.locator(".login-hero").isVisible(), false, "mobile login must use the single-column form layout");
  await anonymous.close();

  const context = await browser.newContext({ baseURL: "http://127.0.0.1:8010", viewport: { width: 1440, height: 1000 } });
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  const loginResponse = await context.request.post("/api/auth/login", {
    form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(loginResponse.status(), 200, await loginResponse.text());
  const page = await context.newPage();
  const routes = [
    ["home-1440-light", "/dashboard/home", "工作台"],
    ["users-1440-light", "/dashboard/users", "用户管理"],
    ["branding-1440-light", "/dashboard/branding", "品牌设置"],
    ["sms-1440-light", "/dashboard/sms", "短信服务"],
    ["navigation-1440-light", "/dashboard/navigation", "导航管理"],
  ];
  for (const [name, path, heading] of routes) {
    await auditPage(page, name, path, target => target.getByRole("heading", { name: heading, exact: true }).waitFor());
    assert.equal(await page.getByRole("heading", { name: heading, exact: true }).count(), 1, `${path} repeats its PageContainer title`);
    if (name === "home-1440-light") {
      const sider = page.locator('.ant-layout-sider');
      await sider.locator('.ant-pro-sider-collapsed-button').click();
      await sider.getByText("平台管理", { exact: true }).click();
      await sider.getByRole("link", { name: "品牌设置", exact: true }).waitFor();
      const standardMetrics = await sider.evaluate(node => ({
        siderWidth: Math.round(node.getBoundingClientRect().width),
        scrollbarWidth: (() => {
          const menu = node.querySelector('.ant-pro-sider-menu');
          const scrollParent = menu?.parentElement;
          return scrollParent ? Math.round(scrollParent.getBoundingClientRect().width - scrollParent.clientWidth) : 0;
        })(),
        visibleRouteCount: Array.from(node.querySelectorAll('a')).filter(link => link.getBoundingClientRect().width > 0).length,
      }));
      assert.equal(standardMetrics.siderWidth, 208);
      assert.equal(standardMetrics.scrollbarWidth, 0, `sidebar scrollbar must not reserve a gutter, got ${standardMetrics.scrollbarWidth}px`);
      assert.ok(standardMetrics.visibleRouteCount > 10, "expanded administration group must expose the real route list");
      const standardScreenshot = `${output}/navigation-standard-1440-light.png`;
      await page.screenshot({ path: standardScreenshot });
      records.push({ name: "navigation-standard-1440-light", path, screenshot: standardScreenshot, ...standardMetrics });
      const focusedScreenshot = ".runtime/browser-checks/sidebar-reference/implementation-sidebar-standard.png";
      await page.screenshot({ path: focusedScreenshot, clip: { x: 0, y: 0, width: 208, height: 1000 } });
      records.push({ name: "navigation-standard-focused-1440-light", path, screenshot: focusedScreenshot, ...standardMetrics });
    }
  }
  assert.equal(await page.locator(".ant-pro-query-filter").count(), 1);
  assert.equal(await page.locator(".ant-pro-table").count(), 1);

  await context.addCookies([{
    name: PREFERENCES_COOKIE,
    value: serializePreferences({ dark: true, locale: "zh-CN" }),
    url: "http://127.0.0.1:8010",
  }]);
  await auditPage(page, "home-1440-dark", "/dashboard/home", target => target.getByRole("heading", { name: "工作台", exact: true }).waitFor());
  assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), "dark");

  await context.addCookies([{
    name: PREFERENCES_COOKIE,
    value: serializePreferences({ dark: false, locale: "zh-CN" }),
    url: "http://127.0.0.1:8010",
  }]);
  await page.setViewportSize({ width: 390, height: 844 });
  await auditPage(page, "home-390-light", "/dashboard/home", target => target.getByRole("heading", { name: "工作台", exact: true }).waitFor());
  await page.locator(".ant-pro-global-header-collapsed-button").click();
  const drawer = page.getByRole("dialog");
  await drawer.waitFor();
  const drawerBox = await drawer.boundingBox();
  assert.ok(drawerBox && Math.abs(drawerBox.width - 208) <= 1, `mobile navigation drawer must be 208px, got ${drawerBox?.width}`);
  assert.equal(await drawer.getByRole("link", { name: "工作台", exact: true }).isVisible(), true);
  assert.equal(await drawer.getByRole("link", { name: "品牌设置", exact: true }).isVisible(), false);
  const mobileScreenshot = `${output}/navigation-drawer-390-light.png`;
  await page.screenshot({ path: mobileScreenshot });
  records.push({ name: "navigation-drawer-390-light", path: "/dashboard/home", screenshot: mobileScreenshot, drawerWidth: Math.round(drawerBox.width) });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  await page.keyboard.press("Escape");

  writeFileSync(`${output}/audit.json`, `${JSON.stringify({ generatedAt: new Date().toISOString(), records }, null, 2)}\n`);
  await context.close();
  console.log(`PASS: standard Ant Design Pro visual QA captured ${records.length} screenshots with no page errors, console errors, duplicate titles or horizontal overflow`);
} finally {
  await browser.close();
}
