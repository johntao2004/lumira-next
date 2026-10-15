import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const adminRoleId = '00000000-0000-0000-0000-000000000052';
const userRoleId = '00000000-0000-0000-0000-000000000053';

function read(relativePath) {
  return readFileSync(path.join(root, relativePath), 'utf8');
}

function sorted(values) {
  return [...values].sort();
}

function permissionCatalogFromSources() {
  const pageSource = read('backend/src/main/java/com/lumira/platform/common/PlatformPageId.java');
  const pageIds = new Map(
    [...pageSource.matchAll(/^\s*([A-Z][A-Z0-9_]*)\("([^"]+)"/gm)]
      .map(([, constant, id]) => [constant, id]),
  );
  const operationSource = read('backend/src/main/java/com/lumira/platform/common/PlatformOperation.java');
  const operations = new Map(
    [...operationSource.matchAll(/^\s*([A-Z][A-Z0-9_]*)\(PlatformPageId\.([A-Z][A-Z0-9_]*),\s*"([^"]+)"/gm)]
      .map(([, constant, page, action]) => [constant, `platform:${pageIds.get(page)}:${action}`]),
  );
  const catalog = read('backend/src/main/java/com/lumira/platform/roles/PlatformPermissionCatalog.java');
  const pageConstants = new Set(
    [...catalog.matchAll(/\bpage\(\s*PlatformPageId\.([A-Z][A-Z0-9_]*)/g)]
      .map(([, constant]) => constant),
  );
  const operationConstants = new Set(
    [...catalog.matchAll(/PlatformOperation\.([A-Z][A-Z0-9_]*)/g)]
      .map(([, constant]) => constant),
  );
  assert.ok(pageConstants.size > 0, 'platform permission catalog must declare pages');
  assert.ok(operationConstants.size > 0, 'platform permission catalog must declare operations');
  for (const constant of pageConstants) {
    assert.ok(pageIds.has(constant), `unknown platform page ${constant}`);
  }
  for (const constant of operationConstants) {
    assert.ok(operations.has(constant), `unknown platform operation ${constant}`);
  }

  const permissions = new Set(
    [...pageConstants].map((constant) => `platform:${pageIds.get(constant)}:access`),
  );
  for (const constant of operationConstants) permissions.add(operations.get(constant));

  const pluginRoot = path.join(root, 'plugins');
  for (const entry of readdirSync(pluginRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const manifestPath = path.join(pluginRoot, entry.name, 'plugin.json');
    if (!existsSync(manifestPath)) continue;
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    for (const permission of manifest.permissions ?? []) permissions.add(permission);
  }
  return permissions;
}

function seedSections() {
  const baseline = read('backend/src/main/resources/db/migration/B52__platform_initialization.sql');
  const roleSectionStart = baseline.indexOf('-- Data for Name: platform_role;');
  const registrationSectionStart = baseline.indexOf('-- Data for Name: platform_registration_policy;');
  const authoritySectionStart = baseline.indexOf('-- Data for Name: platform_role_authority;');
  const nextSectionStart = baseline.indexOf('-- Data for Name:', authoritySectionStart + 1);
  assert.ok(roleSectionStart >= 0 && registrationSectionStart > roleSectionStart);
  assert.ok(authoritySectionStart > registrationSectionStart && nextSectionStart > authoritySectionStart);
  return {
    baseline,
    roles: baseline.slice(roleSectionStart, registrationSectionStart),
    registration: baseline.slice(registrationSectionStart, authoritySectionStart),
    authorities: baseline.slice(authoritySectionStart, nextSectionStart),
  };
}

function roleGrants(authoritySection, roleId) {
  return new Set(
    [...authoritySection.matchAll(new RegExp(`\\('${roleId}', '([^']+)'\\)`, 'g'))]
      .map(([, authority]) => authority),
  );
}

test('B52 seeds editable default roles and binds registration to the self-scoped user role', () => {
  const { baseline, roles, registration } = seedSections();
  const roleRows = [...roles.matchAll(/^\('([0-9a-f-]+)', '([^']+)', '([^']+)', (true|false), (\d+), '([A-Z]+)', (NULL|\d+), (NULL|\d+)\),?;?$/gm)]
    .map(([, id, code, name, enabled, version, scope]) => `${id}|${code}|${name}|${enabled}|${version}|${scope}`);
  assert.deepEqual(sorted(roleRows), sorted([
    `${adminRoleId}|admin|管理员|true|0|ALL`,
    `${userRoleId}|user|普通用户|true|0|SELF`,
  ]));
  assert.match(
    registration,
    new RegExp(`INSERT INTO public\\.platform_registration_policy VALUES \\(1, 1, false, '\\{\\}', '${userRoleId}'`),
  );
  assert.doesNotMatch(baseline, /^\s*INSERT INTO public\.platform_user\s*\(/im);
  assert.doesNotMatch(baseline, /^\s*INSERT INTO public\.platform_user_role\s*\(/im);
  assert.doesNotMatch(baseline, /^\s*INSERT INTO public\.platform_authority\s*\(/im);
});

test('B52 administrator grants exactly the platform catalog and all attached plugin permissions', () => {
  const { authorities } = seedSections();
  const expected = permissionCatalogFromSources();
  const actual = roleGrants(authorities, adminRoleId);
  assert.deepEqual(sorted(actual), sorted(expected));
  assert.ok([...actual].every((authority) => !authority.includes('*')));
});

test('B52 user grants exactly the registration adapter allowlist', () => {
  const { authorities } = seedSections();
  const adapter = read('backend/src/main/java/com/lumira/platform/roles/RegistrationRoleAdapter.java');
  const allowlist = adapter.match(/ALLOWED_AUTHORITIES\s*=\s*Set\.of\(([\s\S]*?)\);/);
  assert.ok(allowlist, 'registration role allowlist must remain explicit');
  const allowed = new Set([...allowlist[1].matchAll(/"([^"]+)"/g)].map(([, authority]) => authority));
  assert.deepEqual(sorted(allowed), sorted([
    'platform:feedback:access',
    'platform:feedback:create',
    'platform:feedback:read',
  ]));
  assert.deepEqual(sorted(roleGrants(authorities, userRoleId)), sorted(allowed));
});
