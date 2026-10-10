import test from 'node:test';
import assert from 'node:assert/strict';
import { routes, navigationGroups, availableNavigation, collapseTabbedNavigation, navigationPageIdForRoute, isSection, isDashboardSection, mergeRuntimeNavigation, runtimeBreadcrumbLabels, runtimeNavigationPageNode, runtimeNavigationTrail } from '../apps/web/src/platform/shell/routes.ts';
import { frontendModuleManifests, frontendRoutes, frontendTabGroups } from '../apps/web/src/platform/modules/catalog.ts';
import { routeTabsForGroup } from '../apps/web/src/platform/modules/route-tabs.ts';
import { safeReturnPath } from '../apps/web/src/platform/auth/return-path.ts';

test('every registered route has exactly one navigation location and a safe return path', () => {
  const sections = navigationGroups.flatMap(group => group.sections);
  const visibleRoutes = frontendRoutes().filter(route => !route.hidden).map(route => route.id);
  assert.equal(new Set(sections).size, sections.length);
  assert.deepEqual([...sections].sort(), [...visibleRoutes].sort());
  for (const routeId of Object.keys(routes)) {
    const path = `/dashboard/${routeId}?page=2#selection`;
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
      { id: 'custom', menuCode: 'qa-page', menuType: 'PAGE', sectionKey: 'custom-page', path: '/dashboard/custom-page', componentKey: 'platform:info', labelKey: 'qaPage', labelDefault: 'QA 页面', routable: true, children: [] },
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
      { id: 'page', menuCode: 'qa-page', menuType: 'PAGE', sectionKey: 'qa-page', path: '/dashboard/qa-page', labelKey: 'qaPage', labelDefault: 'QA 页面', routable: true, children: [] },
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

test('breadcrumbs follow runtime ancestry and path-first tab deep links', () => {
  const tree = [
    { id: 'overview', menuCode: 'overview', menuType: 'CATALOG', sectionKey: null, labelDefault: '概览', children: [
      { id: 'home', menuCode: 'home', menuType: 'PAGE', sectionKey: 'home', path: '/dashboard/home', labelDefault: '工作台', routable: true, children: [] },
    ] },
    { id: 'users', menuCode: 'user-management', menuType: 'CATALOG', sectionKey: null, labelDefault: '用户管理', children: [
      { id: 'online-users', menuCode: 'online-users', menuType: 'PAGE', sectionKey: 'online-users', path: '/dashboard/online-users', labelDefault: '在线用户', routable: true, children: [] },
    ] },
  ];
  const homeTrail = runtimeNavigationTrail(tree, 'home', '/dashboard/home');
  const tabTrail = runtimeNavigationTrail(tree, 'online-users', '/dashboard/online-users');
  const label = node => node.labelDefault;

  assert.deepEqual(runtimeBreadcrumbLabels(homeTrail, '备用标题', label), ['概览', '工作台']);
  assert.deepEqual(runtimeBreadcrumbLabels(tabTrail, '备用标题', label), ['用户管理', '在线用户']);
  assert.equal(runtimeNavigationPageNode(tree, 'wrong-section', '/dashboard/online-users').sectionKey, 'online-users');
  assert.deepEqual(runtimeBreadcrumbLabels(null, '当前页面', label), ['当前页面']);
});

test('runtime breadcrumbs preserve same-named ancestors and titles', () => {
  const tree = [{ id: 'outer', menuType: 'CATALOG', sectionKey: null, labelDefault: '同名', children: [
    { id: 'inner', menuType: 'CATALOG', sectionKey: null, labelDefault: '同名', children: [
      { id: 'page', menuType: 'PAGE', sectionKey: 'custom-page', path: '/dashboard/custom-page', labelDefault: '同名', routable: true, children: [] },
    ] },
  ] }];
  const trail = runtimeNavigationTrail(tree, 'custom-page', '/dashboard/custom-page');
  assert.deepEqual(runtimeBreadcrumbLabels(trail, '备用标题', node => node.labelDefault), ['同名', '同名', '同名']);
});

test('denied PAGE parents remain ancestry only while permitted dynamic children remain visible', () => {
  const tree = [{ id: 'catalog', menuType: 'CATALOG', menuCode: 'qa-catalog', sectionKey: null, labelDefault: '自定义目录', children: [
    { id: 'denied-parent', menuType: 'PAGE', menuCode: 'hidden-parent', sectionKey: 'hidden-parent', path: '/dashboard/hidden-parent', labelDefault: '无权父页', routable: false, children: [
      { id: 'child', menuType: 'PAGE', menuCode: 'visible-child', sectionKey: 'visible-child', path: '/dashboard/visible-child', labelDefault: '可见子页', routable: true, children: [] },
    ] },
  ] }];
  const merged = mergeRuntimeNavigation([], tree);
  const catalog = merged.find(group => group.id === 'qa-catalog');
  const childTrail = runtimeNavigationTrail(tree, 'visible-child', '/dashboard/visible-child');

  assert.equal(runtimeNavigationTrail(tree, 'hidden-parent', '/dashboard/hidden-parent'), null);
  assert.deepEqual(catalog.pages.map(page => page.id), ['visible-child']);
  assert.deepEqual(runtimeBreadcrumbLabels(childTrail, '备用标题', node => node.labelDefault), ['自定义目录', '无权父页', '可见子页']);
});

test('denied root PAGE parents retain routable child pages in a structural sidebar group', () => {
  const tree = [{ id: 'root-parent', menuType: 'PAGE', menuCode: 'hidden-root', sectionKey: 'hidden-root', path: '/dashboard/hidden-root', labelDefault: '根父页', routable: false, children: [
    { id: 'root-child', menuType: 'PAGE', menuCode: 'root-child', sectionKey: 'root-child', path: '/dashboard/root-child', labelDefault: '根子页', routable: true, children: [] },
  ] }];
  const merged = mergeRuntimeNavigation([], tree);
  const structuralGroup = merged.find(group => group.id === 'root-ancestor:root-parent');

  assert.ok(structuralGroup);
  assert.equal(structuralGroup.standalone, undefined);
  assert.equal(structuralGroup.labelDefault, '根父页');
  assert.deepEqual(structuralGroup.pages.map(page => page.id), ['root-child']);
  assert.equal(runtimeNavigationTrail(tree, 'hidden-root', '/dashboard/hidden-root'), null);
});

test('runtime labels override static sidebar metadata without granting static access', () => {
  const tree = [{ id: 'user-management', menuCode: 'user-management', menuType: 'CATALOG', sectionKey: null, labelKey: 'userManagement', labelDefault: '运行时用户管理', children: [
    { id: 'users', menuCode: 'users', menuType: 'PAGE', sectionKey: 'users', path: '/dashboard/users', labelKey: 'runtimeUsers', labelDefault: '运行时用户页', routable: true, children: [] },
  ] }];
  const merged = mergeRuntimeNavigation(availableNavigation(['platform:users:read'], [], []), tree);
  const group = merged.find(item => item.id === 'user-management');

  assert.equal(group.labelDefault, '运行时用户管理');
  assert.equal(group.pages[0].labelDefault, '运行时用户页');
  const withoutPermission = mergeRuntimeNavigation(availableNavigation([]), tree);
  assert.equal(withoutPermission.flatMap(item => item.pages).some(page => page.id === 'users'), false);
});

test('security and registration settings share one sidebar entry with direct tab routes', () => {
  const groups = collapseTabbedNavigation(availableNavigation(['platform:settings:read']));
  const administration = groups.find(group => group.id === 'administration');
  const settingRoutes = administration.pages.map(page => page.id);

  assert.ok(settingRoutes.includes('security'));
  for (const tab of ['authentication', 'passkey', 'wechat', 'captcha', 'registration']) {
    assert.ok(!settingRoutes.includes(tab), `${tab} should be a tab, not a sidebar entry`);
    assert.equal(navigationPageIdForRoute(tab, groups), 'security');
    assert.ok(isSection(tab), `${tab} deep link remains a registered route`);
  }

  const serverOwnedSubset = mergeRuntimeNavigation(availableNavigation(['platform:settings:read']), [
    { id: 'security', menuCode: 'security', menuType: 'PAGE', sectionKey: 'security', path: '/dashboard/security', children: [] },
    { id: 'passkey', menuCode: 'passkey', menuType: 'PAGE', sectionKey: 'passkey', path: '/dashboard/passkey', children: [] },
  ]);
  const subsetNavigation = collapseTabbedNavigation(serverOwnedSubset);
  assert.deepEqual(subsetNavigation.find(group => group.id === 'administration').pages.map(page => page.id), ['security']);
  for (const tab of ['authentication', 'passkey', 'wechat', 'captcha', 'registration']) {
    assert.equal(navigationPageIdForRoute(tab, subsetNavigation), 'security');
  }

  const settingLabels = {
    security: '安全策略',
    authentication: '认证方式',
    passkey: '通行密钥设置',
    wechat: '微信登录设置',
    captcha: '人机验证设置',
    registration: '公开注册设置',
  };
  const settingsTree = [{ id: 'administration', menuCode: 'administration', menuType: 'CATALOG', sectionKey: null, labelDefault: '平台管理', children: Object.entries(settingLabels).map(([id, label]) => ({
    id,
    menuCode: id,
    menuType: 'PAGE',
    sectionKey: id,
    path: `/dashboard/${id}`,
    labelDefault: label,
    routable: true,
    children: [],
  })) }];
  for (const [routeId, title] of Object.entries(settingLabels)) {
    const trail = runtimeNavigationTrail(settingsTree, routeId, `/dashboard/${routeId}`);
    assert.deepEqual(runtimeBreadcrumbLabels(trail, '备用标题', node => node.labelDefault), ['平台管理', title]);
  }
});

test('five tab groups derive labels, aliases, and sidebar anchors from module manifests', () => {
  assert.deepEqual(frontendTabGroups.map(({ moduleId, id, routes, anchor, collapse }) => ({ moduleId, id, routes, anchor, collapse })), [
    { moduleId: 'settings', id: 'personalization', routes: ['branding', 'watermark', 'agreements', 'maintenance'], anchor: 'branding', collapse: 'when-all-visible' },
    { moduleId: 'settings', id: 'message-channels', routes: ['smtp', 'sms'], anchor: 'smtp', collapse: 'when-all-visible' },
    { moduleId: 'settings', id: 'authentication-security', routes: ['security', 'authentication', 'passkey', 'wechat', 'captcha', 'registration'], anchor: 'security', collapse: 'when-anchor-visible' },
    { moduleId: 'directory', id: 'user-activity', routes: ['users', 'online-users'], anchor: 'users', collapse: 'when-all-visible' },
    { moduleId: 'communication', id: 'delivery-records', routes: ['mail-deliveries', 'sms-deliveries'], anchor: 'mail-deliveries', collapse: 'when-all-visible' },
  ]);
  for (const tabGroup of frontendTabGroups) {
    const manifest = frontendModuleManifests.find(candidate => candidate.id === tabGroup.moduleId);
    const tabs = routeTabsForGroup(manifest, tabGroup.id, Object.fromEntries(tabGroup.routes.map(id => [id, id])));
    assert.deepEqual(tabs.map(tab => tab.key), tabGroup.routes);
    assert.deepEqual(tabs.map(tab => tab.path), tabGroup.routes.map(id => `/dashboard/${id}`));
    assert.deepEqual(tabs.map(tab => tab.label), tabGroup.routes.map(id => {
      const route = manifest.routes.find(candidate => candidate.id === id);
      return route.tabLabel ?? route.label;
    }));
  }
  const settings = frontendModuleManifests.find(candidate => candidate.id === 'settings');
  const partial = routeTabsForGroup(settings, 'personalization', { branding: 'branding', watermark: 'watermark' });
  assert.deepEqual(partial.map(tab => tab.key), ['branding', 'watermark']);
});

test('non-security sidebar aliases collapse only when every declared route is present', () => {
  const complete = [{ id: 'administration', label: 'administration', subgroup: null, sections: [], pages: [
    'branding', 'watermark', 'agreements', 'maintenance', 'smtp', 'sms', 'security', 'authentication', 'passkey', 'wechat', 'captcha', 'registration',
  ].map(id => ({ id, label: id, permission: null })) }];
  const collapsed = collapseTabbedNavigation(complete)[0].pages.map(page => page.id);
  assert.deepEqual(collapsed, ['branding', 'smtp', 'security']);
  assert.equal(navigationPageIdForRoute('watermark', collapseTabbedNavigation(complete)), 'branding');

  const partial = [{ ...complete[0], pages: complete[0].pages.filter(page => ['branding', 'watermark', 'maintenance', 'security', 'passkey'].includes(page.id)) }];
  const visible = collapseTabbedNavigation(partial)[0].pages.map(page => page.id);
  assert.ok(visible.includes('branding'));
  assert.ok(visible.includes('watermark'));
  assert.ok(visible.includes('maintenance'));
  assert.deepEqual(visible.filter(id => ['security', 'passkey'].includes(id)), ['security']);
});

test('audit navigation requires both module capability and permission',()=>{
 const pages=(grants,modules)=>availableNavigation(grants,modules).flatMap(g=>g.pages.map(p=>p.id));
 assert.equal(pages(['platform:audit:read'],[]).includes('audit'),false);
 assert.equal(pages([],['audit-console']).includes('audit'),false);
 assert.equal(pages(['platform:audit:read'],['audit-console']).includes('audit'),true);
});

test('retired standalone AI pages are absent while model connections remain routable', () => {
  assert.equal(isSection('ai-capabilities'), false);
  assert.equal(isSection('ai-judgment'), false);
  assert.equal(isSection('ai-models'), true);
  const pages = availableNavigation(['platform:settings:read'])
    .flatMap(group => group.pages.map(page => page.id));
  assert.ok(pages.includes('ai-models'));
  assert.ok(!pages.includes('ai-capabilities'));
  assert.ok(!pages.includes('ai-judgment'));
});
