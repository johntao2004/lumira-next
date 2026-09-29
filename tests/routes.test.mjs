import test from 'node:test';
import assert from 'node:assert/strict';
import { routes, navigationGroups, availableNavigation, isSection, isDashboardSection, mergeRuntimeNavigation } from '../apps/web/src/platform/shell/routes.ts';
import { safeReturnPath } from '../apps/web/src/platform/auth/return-path.ts';

test('every registered route has exactly one navigation location and a safe return path', () => {
  const sections = navigationGroups.flatMap(group => group.sections);
  assert.equal(new Set(sections).size, sections.length);
  assert.deepEqual([...sections].sort(), Object.keys(routes).sort());
  for (const section of sections) {
    const path = `/dashboard/${section}?page=2#selection`;
    assert.equal(safeReturnPath(path), path);
  }
  for (const section of ['home/extra', 'Home', '']) {
    assert.equal(isSection(section), false);
    assert.equal(safeReturnPath(`/dashboard/${section}`), '/dashboard/home');
  }
  assert.equal(isDashboardSection('custom-page'), true);
  assert.equal(safeReturnPath('/dashboard/custom-page'), '/dashboard/custom-page');
  assert.equal(isDashboardSection('constructor'), true);
});

test('server-owned custom pages join the matching navigation group safely', () => {
  const groups = availableNavigation(['platform:users:read'], [], []);
  const merged = mergeRuntimeNavigation(groups, [
    { id: 'root', menuCode: 'administration', menuType: 'CATALOG', sectionKey: null, children: [
      { id: 'custom', menuCode: 'qa-page', menuType: 'PAGE', sectionKey: 'custom-page', path: '/dashboard/custom-page', componentKey: 'platform:info', labelKey: 'qaPage', labelDefault: 'QA 页面', children: [] },
    ] },
  ]);
  const administration = merged.find((group) => group.id === 'administration');
  assert.equal(administration.pages.at(-1).id, 'custom-page');
  assert.equal(administration.pages.at(-1).path, '/dashboard/custom-page');
  assert.equal(administration.pages.at(-1).componentKey, 'platform:info');
  assert.equal(administration.pages.at(-1).dynamic, true);
});

test('server-owned custom root catalogs become top-level navigation groups', () => {
  const groups = availableNavigation(['platform:users:read'], [], []);
  const merged = mergeRuntimeNavigation(groups, [
    { id: 'catalog', menuCode: 'qa-catalog', menuType: 'CATALOG', sectionKey: null, labelKey: 'qaCatalog', labelDefault: 'QA 分组', children: [
      { id: 'page', menuCode: 'qa-page', menuType: 'PAGE', sectionKey: 'qa-page', path: '/dashboard/qa-page', labelKey: 'qaPage', labelDefault: 'QA 页面', children: [] },
    ] },
  ]);
  const catalog = merged.find((group) => group.id === 'qa-catalog');
  assert.equal(catalog.labelDefault, 'QA 分组');
  assert.deepEqual(catalog.pages.map((page) => page.id), ['qa-page']);
});

test('navigation removes empty groups and only exposes independently granted pages', () => {
  assert.deepEqual(availableNavigation([]).map(group => group.id), ['overview']);
  const groups = availableNavigation(['platform:users:read', 'platform:roles:write'], [], []);
  const userManagement = groups.find(group => group.id === 'user-management');
  assert.deepEqual(userManagement.pages.map(page => page.id), ['users']);
  assert.equal(groups.some(group => group.id === 'administration'), false);
  assert.deepEqual(availableNavigation(['platform:users:read'], [], ['platform:users:read'])
    .find(group => group.id === 'user-management').pages.map(page => page.id), ['users', 'online-users']);
});

test('audit navigation requires both module capability and permission',()=>{
 const pages=(grants,modules)=>availableNavigation(grants,modules).flatMap(g=>g.pages.map(p=>p.id));
 assert.equal(pages(['platform:audit:read'],[]).includes('audit'),false);
 assert.equal(pages([],['audit-console']).includes('audit'),false);
 assert.equal(pages(['platform:audit:read'],['audit-console']).includes('audit'),true);
});
