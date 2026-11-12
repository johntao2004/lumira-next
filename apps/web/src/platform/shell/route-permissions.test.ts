import test from "node:test";
import assert from "node:assert/strict";
import { availableNavigation, canAccessSection, canOperateSection } from "./routes.ts";
import { frontendModuleManifests } from "../modules/catalog.ts";
import { routeTabsForGroup } from "../modules/route-tabs.ts";

const pluginModules = new Set(["feedback", "localization", "sensitive-words", "mfa", "payment-core", "wechat-pay", "alipay-pay"]);

test("every platform page has an explicit stable access code and unique page-owned operation codes", () => {
  const operationOwners = new Map<string, string>();
  for (const module of frontendModuleManifests.filter(({ id }) => !pluginModules.has(id))) {
    for (const route of module.routes) {
      if (route.permission === null) {
        assert.equal(route.operations, undefined, `${route.id} remains a self-service route`);
        continue;
      }
      assert.equal(route.permission, `platform:${route.id}:access`, `${route.id} has its own access authority`);
      assert.ok(route.accessScope === "global" || route.accessScope === "scoped", `${route.id} declares its access scope`);
      for (const [actionId, code] of Object.entries(route.operations ?? {})) {
        assert.ok(code.startsWith(`platform:${route.id}:`), `${route.id}:${actionId} has a page-owned code`);
        assert.equal(operationOwners.has(code), false, `${code} has one route owner`);
        operationOwners.set(code, route.id);
      }
    }
  }
  assert.ok(operationOwners.has("platform:ai-console:create-user"));
  assert.ok(operationOwners.has("platform:ai-models:test"));
  assert.equal(operationOwners.has("platform:users:create-user"), false);
});

test("an operation requires both its page access and its own action grant", () => {
  const pageAccess = "platform:users:access";
  const create = "platform:users:create";

  assert.equal(canOperateSection("users", "create", [pageAccess], ["directory"], [pageAccess]), false);
  assert.equal(canOperateSection("users", "create", [create], ["directory"], [pageAccess, create]), false);
  assert.equal(canOperateSection("users", "create", [pageAccess], ["directory"], [pageAccess]), false);
  assert.equal(canOperateSection("users", "create", [pageAccess], ["directory"], [pageAccess, create]), true);
  assert.equal(canOperateSection("users", "create", [pageAccess], ["directory"], [pageAccess, "platform:ai-console:create-user"]), false);
});

test("target-scoped actions use effective grants while global actions require global grants", () => {
  const access = "platform:users:access";
  const update = "platform:users:update";
  assert.equal(canOperateSection("users", "update", [access, update], ["directory"], [access]), true);
  assert.equal(canOperateSection("users", "create", [access, "platform:users:create"], ["directory"], [access]), false);
  assert.equal(canOperateSection("users", "assignRoles", [access, "platform:users:assign-roles"], ["directory"], [access]), true);

  const aiAccess = "platform:ai-console:access";
  const readUsers = "platform:ai-console:read-users";
  assert.equal(canOperateSection("ai-console", "readUsers", [aiAccess, readUsers], ["ai"], []), true);
  assert.equal(canOperateSection("ai-console", "updateUser", [aiAccess, "platform:ai-console:update-user"], ["ai"], []), true);
});

test("page access uses the catalog's explicit global or caller-scoped authority scope", () => {
  assert.equal(canAccessSection("users", ["platform:users:access"], ["directory"], []), true);
  assert.equal(canAccessSection("users", [], ["directory"], ["platform:users:access"]), false);
  assert.equal(canAccessSection("online-users", ["platform:online-users:access"], ["directory"], []), false);
  assert.equal(canAccessSection("online-users", [], ["directory"], ["platform:online-users:access"]), true);
});

test("page access controls navigation separately from action grants", () => {
  const actionOnly = availableNavigation(["platform:users:create"], ["directory"], ["platform:users:create"])
    .flatMap(({ pages }) => pages.map(({ id }) => id));
  assert.equal(actionOnly.includes("users"), false);

  const usersOnly = availableNavigation(["platform:users:access"], ["directory"], ["platform:users:access"])
    .flatMap(({ pages }) => pages.map(({ id }) => id));
  assert.equal(usersOnly.includes("users"), true);
  assert.equal(usersOnly.includes("online-users"), false);
  assert.equal(canAccessSection("online-users", ["platform:users:access"], ["directory"], ["platform:users:access"]), false);
});

test("site settings tabs include only children with their own page access", () => {
  const settings = frontendModuleManifests.find(({ id }) => id === "site-console");
  assert.ok(settings);
  const access = new Set(["watermark", "registration"]);
  const childrenByRoute = Object.fromEntries(settings.routes.map(({ id }) => [id, access.has(id) ? id : undefined]));
  const visible = routeTabsForGroup(settings, "personalization", childrenByRoute);
  assert.deepEqual(visible.map(({ key }) => key), ["watermark"]);
  assert.equal(canAccessSection("smtp", ["platform:smtp:access"], ["delivery-settings"], ["platform:smtp:access"]), true);
  assert.equal(canAccessSection("sms", ["platform:smtp:access"], ["delivery-settings"], ["platform:smtp:access"]), false);
});


test("directory authority grants do not expose unloaded pages or operations", () => {
  const grants = ["platform:users:access", "platform:users:create", "platform:online-users:access"];
  assert.equal(canAccessSection("users", grants, [], grants), false);
  assert.equal(canAccessSection("online-users", grants, [], grants), false);
  assert.equal(canOperateSection("users", "create", grants, [], grants), false);
  assert.equal(availableNavigation(grants, [], grants).some(({ pages }) => pages.some(({ id }) => id === "users")), false);
});


test("AI pages and actions require their optional provider to be enabled", () => {
  const grants = ["platform:ai-console:access", "platform:ai-console:read-users"];
  assert.equal(canAccessSection("ai-console", grants, [], grants), false);
  assert.equal(canOperateSection("ai-console", "readUsers", grants, [], grants), false);
  assert.equal(canOperateSection("ai-console", "readUsers", grants, ["ai"], grants), true);
});
