import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { routes, navigationGroups, availableNavigation, canAccessSection, canOperateSection, collapseTabbedNavigation, navigationPageIdForRoute, isSection, isDashboardSection, mergeRuntimeNavigation, runtimeBreadcrumbLabels, runtimeNavigationPageNode, runtimeNavigationTrail } from '../apps/web/src/platform/shell/routes.ts';
import { frontendModuleManifests, frontendRoutes, frontendTabGroups } from '../apps/web/src/platform/modules/catalog.ts';
import { createFrontendPageRegistry } from '../apps/web/src/platform/modules/contracts.ts';
import { routeTabsForGroup } from '../apps/web/src/platform/modules/route-tabs.ts';
import { safeReturnPath } from '../apps/web/src/platform/auth/return-path.ts';
import { languages, loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
import { mfaManifest } from '../plugins/mfa/frontend/manifest.ts';
import { paymentCoreManifest } from '../plugins/payment-core/frontend/manifest.ts';
import { wechatPayManifest } from '../plugins/wechat-pay/frontend/manifest.ts';
import { alipayPayManifest } from '../plugins/alipay-pay/frontend/manifest.ts';

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

test('retired account-draft page is not routable or present in navigation', () => {
  assert.equal(isSection('account-drafts'), false);
  assert.equal(Object.hasOwn(routes, 'account-drafts'), false);
  assert.equal(frontendRoutes().some(route => route.id === 'account-drafts'), false);
});

test('navigation groups own the intended routes while hidden account pages stay out of menus', () => {
  assert.deepEqual(navigationGroups.map(group => group.id), [
    'overview', 'ai-management', 'user-management', 'content-management',
    'communication', 'payments', 'operations', 'administration',
  ]);
  assert.deepEqual(navigationGroups.map(group => [group.id, group.label]), [
    ['overview', 'overview'],
    ['ai-management', 'aiManagement'],
    ['user-management', 'userManagement'],
    ['content-management', 'contentManagement'],
    ['communication', 'communicationCenter'],
    ['payments', 'paymentManagement'],
    ['operations', 'operationsCenter'],
    ['administration', 'administration'],
  ]);
  const routesById = new Map(frontendRoutes().map(route => [route.id, route]));
  const expectedOwners = {
    home: 'overview',
    files: 'overview',
    'ai-console': 'ai-management',
    'ai-models': 'ai-management',
    users: 'user-management',
    'online-users': 'user-management',
    roles: 'user-management',
    organizations: 'user-management',
    'profile-fields': 'user-management',
    security: 'user-management',
    authentication: 'user-management',
    passkey: 'user-management',
    wechat: 'user-management',
    captcha: 'user-management',
    registration: 'user-management',
    'mfa-management': 'user-management',
    'files-all': 'content-management',
    dictionaries: 'content-management',
    'sensitive-words': 'content-management',
    localization: 'content-management',
    notifications: 'communication',
    smtp: 'communication',
    sms: 'communication',
    'mail-deliveries': 'communication',
    'sms-deliveries': 'communication',
    feedback: 'communication',
    monitoring: 'operations',
    'api-docs': 'operations',
    audit: 'operations',
    'event-outbox': 'operations',
    modules: 'administration',
    branding: 'administration',
    watermark: 'administration',
    agreements: 'administration',
    maintenance: 'administration',
    navigation: 'administration',
  };
  for (const [routeId, group] of Object.entries(expectedOwners)) {
    assert.equal(routesById.get(routeId)?.group, group, `${routeId} belongs to ${group}`);
  }
  for (const routeId of ['account-profile', 'account-security', 'account-authentication-security', 'account-role-preview', 'account-wechat', 'account-password', 'account-sessions', 'mfa-authenticator', 'file-retention']) {
    const route = routesById.get(routeId);
    assert.equal(route?.hidden, true, `${routeId} stays hidden`);
    assert.ok(!navigationGroups.some(group => group.sections.includes(routeId)), `${routeId} is not a menu item`);
  }
  for (const routeId of ['account-profile', 'account-security', 'account-authentication-security', 'account-role-preview', 'account-wechat', 'account-password', 'account-sessions']) {
    assert.equal(routesById.get(routeId)?.group, 'user-management');
  }
  assert.deepEqual(availableNavigation([]).find(group => group.id === 'overview').pages.map(page => page.id), ['home']);
  assert.deepEqual(availableNavigation([], ['personal-files']).find(group => group.id === 'overview').pages.map(page => page.id), ['home', 'files']);
});

test('static manifest ordering matches the current built-in navigation order', () => {
  const expectedStaticSections = {
    overview: ['home', 'files'],
    'ai-management': ['ai-console', 'ai-models'],
    'user-management': [
      'users', 'online-users', 'roles', 'organizations', 'profile-fields', 'security',
      'authentication', 'passkey', 'wechat', 'captcha', 'registration', 'mfa-management',
    ],
    'content-management': ['files-all', 'dictionaries', 'sensitive-words', 'print-templates', 'localization'],
    communication: ['notifications', 'smtp', 'sms', 'mail-deliveries', 'sms-deliveries', 'feedback'],
    payments: [],
    operations: ['monitoring', 'api-docs', 'audit', 'event-outbox'],
    administration: ['modules', 'branding', 'watermark', 'agreements', 'maintenance', 'navigation'],
  };
  for (const [groupId, expected] of Object.entries(expectedStaticSections)) {
    assert.deepEqual(navigationGroups.find(group => group.id === groupId).sections, expected, `${groupId} follows the built-in order`);
  }
  assert.deepEqual(paymentCoreManifest.routes.map(route => route.order), [10]);
  assert.deepEqual(wechatPayManifest.routes.map(route => route.order), [20, 40]);
  assert.deepEqual(alipayPayManifest.routes.map(route => route.order), [30, 50]);
});

test('plugin routes stay within their feature ownership and tab groups share one navigation group', () => {
  for (const manifest of frontendModuleManifests) {
    for (const group of manifest.tabGroups ?? []) {
      const routeGroups = new Set(group.routes.map(routeId => manifest.routes.find(route => route.id === routeId)?.group));
      assert.equal(routeGroups.size, 1, `${manifest.id}:${group.id} tabs share a navigation group`);
    }
  }
  assert.equal(mfaManifest.id, 'mfa');
  assert.equal(mfaManifest.routes[0].hidden, true);
  assert.equal(isSection('mfa-authenticator'), true);
  assert.equal(canAccessSection('mfa-authenticator', [], []), false);
  assert.equal(canAccessSection('mfa-authenticator', [], ['mfa']), true);
  const mfaAccess = 'platform:mfa:management-access';
  const mfaRead = 'platform:mfa:management-read';
  const mfaReset = 'platform:mfa:management-reset';
  assert.equal(canAccessSection('mfa-management', [mfaAccess], ['mfa']), true);
  assert.equal(canAccessSection('mfa-management', [mfaRead], ['mfa']), false);
  assert.equal(canOperateSection('mfa-management', 'read', [mfaAccess], ['mfa'], [mfaAccess]), false);
  assert.equal(canOperateSection('mfa-management', 'read', [mfaAccess, mfaRead], ['mfa'], [mfaAccess, mfaRead]), true);
  assert.equal(canOperateSection('mfa-management', 'reset', [mfaAccess], ['mfa'], [mfaAccess]), false);
  assert.equal(canOperateSection('mfa-management', 'reset', [mfaAccess, mfaReset], ['mfa'], [mfaAccess]), true);
  assert.ok(!availableNavigation([mfaRead], ['mfa']).flatMap(group => group.pages).some(page => page.id.startsWith('mfa-')));
  assert.deepEqual(availableNavigation([mfaAccess], ['mfa'], [mfaAccess])
    .find(group => group.id === 'user-management').pages.filter(page => page.id.startsWith('mfa-')).map(page => page.id), ['mfa-management']);
  assert.deepEqual(mfaManifest.routes.map(route => [route.id, route.group]), [
    ['mfa-authenticator', 'user-management'],
    ['mfa-management', 'user-management'],
  ]);
  assert.equal(paymentCoreManifest.id, 'payment-core');
  assert.equal(paymentCoreManifest.routes[0].group, 'payments');
  assert.equal(paymentCoreManifest.routes[0].permission, 'platform:payment-core:orders-access');
  assert.equal(paymentCoreManifest.routes[0].global, true);
  for (const route of wechatPayManifest.routes) {
    assert.equal(route.group, 'payments');
    assert.ok(route.permission.startsWith('platform:wechat-pay:'));
    assert.ok(Object.values(route.operations ?? {}).every(code => code.startsWith('platform:wechat-pay:')));
    assert.equal(route.global, true);
  }
  for (const route of alipayPayManifest.routes) {
    assert.equal(route.group, 'payments');
    assert.ok(route.permission.startsWith('platform:alipay-pay:'));
    assert.ok(Object.values(route.operations ?? {}).every(code => code.startsWith('platform:alipay-pay:')));
    assert.equal(route.global, true);
  }
});

test('hidden MFA runtime page stays out of navigation while its plugin renderer remains registered', () => {
  const authorities = ['platform:mfa:management-access'];
  const enabledModules = ['mfa'];
  const groups = availableNavigation(authorities, enabledModules, authorities);
  const merged = mergeRuntimeNavigation(groups, [
    { id: 'users', menuCode: 'user-management', menuType: 'CATALOG', sectionKey: null, children: [
      { id: 'mfa-authenticator', menuCode: 'mfa-authenticator', menuType: 'PAGE', sectionKey: 'mfa-authenticator', path: '/dashboard/mfa-authenticator', routable: true, children: [] },
      { id: 'mfa-management', menuCode: 'mfa-management', menuType: 'PAGE', sectionKey: 'mfa-management', path: '/dashboard/mfa-management', routable: true, children: [] },
    ] },
  ]);
  const userManagement = merged.find(group => group.id === 'user-management');
  assert.ok(userManagement.pages.some(page => page.id === 'mfa-management'));
  assert.ok(!userManagement.pages.some(page => page.id === 'mfa-authenticator'));

  const registry = createFrontendPageRegistry([]);
  const unregister = registry.registerPlugin({
    ...mfaManifest,
    pages: {
      'mfa-authenticator': () => 'authenticator renderer',
      'mfa-management': () => 'management renderer',
    },
  });
  const context = { authorities, hasGlobal: permission => authorities.includes(permission) };
  assert.equal(registry.owner('mfa-authenticator'), 'mfa');
  assert.equal(registry.render('mfa-authenticator', context), 'authenticator renderer');
  unregister();
  assert.equal(registry.owner('mfa-authenticator'), null);
});

test('navigation group and anchor labels are translated across all supported locales', async () => {
  const keys = [
    'overview', 'aiManagement', 'userManagement', 'contentManagement',
    'communicationCenter', 'paymentManagement', 'operationsCenter', 'administration',
    'siteSettings', 'messageChannels', 'deliveryRecords', 'authenticationSecurity',
  ];
  for (const { value: locale } of languages) {
    const messages = await loadLocaleMessages(locale);
    for (const key of keys) {
      assert.ok(messages[key].trim(), `${locale}:${key} has a translation`);
      assert.notEqual(messages[key], key, `${locale}:${key} does not fall back to the key`);
    }
  }
  const simplifiedChinese = await loadLocaleMessages('zh-CN');
  assert.equal(simplifiedChinese.overview, '工作空间');
  assert.equal(simplifiedChinese.userManagement, '用户与权限');
  assert.equal(simplifiedChinese.contentManagement, '内容与资源');
  assert.equal(simplifiedChinese.communicationCenter, '消息与反馈');
  assert.equal(simplifiedChinese.paymentManagement, '支付管理');
  assert.equal(simplifiedChinese.operationsCenter, '系统运维');
  assert.equal(simplifiedChinese.administration, '平台配置');
  assert.equal(simplifiedChinese.siteSettings, '站点设置');
  assert.equal(simplifiedChinese.messageChannels, '消息渠道');
  assert.equal(simplifiedChinese.deliveryRecords, '投递记录');
  assert.equal(simplifiedChinese.authenticationSecurity, '认证与安全');
});

test('server-owned custom pages join the matching navigation group safely', () => {
  const groups = availableNavigation(['platform:users:access'], ['directory'], []);
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
  const groups = availableNavigation(['platform:users:access'], ['directory'], []);
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
  const groups = availableNavigation(['platform:users:access', 'platform:roles:update'], ['directory'], []);
  const userManagement = groups.find(group => group.id === 'user-management');
  assert.deepEqual(userManagement.pages.map(page => page.id), ['users']);
  assert.equal(groups.some(group => group.id === 'administration'), false);
  assert.deepEqual(availableNavigation(['platform:users:access'], ['directory'], ['platform:online-users:access'])
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

test('print templates resolves through its registered section when runtime navigation is correctly seeded', () => {
  const baseline = readFileSync(new URL('../backend/src/main/resources/db/migration/B52__platform_initialization.sql', import.meta.url), 'utf8');
  const tableDefinition = baseline.match(/CREATE TABLE public\.platform_navigation_menu \(([\s\S]*?)^\);/m)?.[1];
  assert.ok(tableDefinition, 'B52 defines platform_navigation_menu');
  const columns = tableDefinition.split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('CONSTRAINT'))
    .map(line => line.match(/^([a-z_][a-z0-9_]*)\s+/)?.[1])
    .filter(Boolean);
  const insert = baseline.split(/\r?\n/).find(line => line.startsWith('INSERT INTO public.platform_navigation_menu VALUES ')
    && line.includes("'print-templates'"));
  assert.ok(insert, 'B52 seeds the print-templates navigation page');
  const tuple = insert.match(/ VALUES \((.*)\);$/)?.[1];
  assert.ok(tuple, 'print-templates seed is a VALUES tuple');
  const values = [];
  let start = 0;
  let quoted = false;
  for (let index = 0; index < tuple.length; index++) {
    if (tuple[index] === "'") {
      if (quoted && tuple[index + 1] === "'") index++;
      else quoted = !quoted;
    } else if (tuple[index] === ',' && !quoted) {
      values.push(tuple.slice(start, index).trim());
      start = index + 1;
    }
  }
  values.push(tuple.slice(start).trim());
  assert.equal(values.length, columns.length, 'B52 row values align with the declared table columns');
  const row = Object.fromEntries(columns.map((column, index) => [
    column,
    values[index] === 'NULL'
      ? null
      : values[index].startsWith("'") && values[index].endsWith("'")
        ? values[index].slice(1, -1).replace(/''/g, "'")
        : values[index],
  ]));
  const page = {
    id: row.id,
    menuCode: row.menu_code,
    menuType: row.menu_type,
    sectionKey: row.section_key,
    path: row.path,
    componentKey: row.component_key,
    routable: true,
    children: [],
  };
  const target = runtimeNavigationPageNode([page], 'print-templates', '/dashboard/print-templates');
  const trail = runtimeNavigationTrail([page], 'print-templates', '/dashboard/print-templates');
  const navigation = mergeRuntimeNavigation(
    availableNavigation(['platform:print-templates:access'], ['directory'], ['platform:print-templates:access']),
    [page],
  );

  assert.equal(row.menu_code, 'print-templates');
  assert.equal(row.menu_type, 'PAGE');
  assert.equal(row.section_key, 'print-templates');
  assert.equal(row.path, '/dashboard/print-templates');
  assert.equal(isSection('print-templates'), true);
  assert.equal(frontendRoutes().some(route => route.id === 'print-templates'), true);
  assert.equal(target, page);
  assert.deepEqual(trail, [page]);
  assert.equal(navigation.flatMap(group => group.pages).some(item => item.id === 'print-templates'), true);
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
  const merged = mergeRuntimeNavigation(availableNavigation(['platform:users:access'], ['directory'], []), tree);
  const group = merged.find(item => item.id === 'user-management');

  assert.equal(group.labelDefault, '运行时用户管理');
  assert.equal(group.pages[0].labelDefault, '运行时用户页');
  const withoutPermission = mergeRuntimeNavigation(availableNavigation([], ['directory']), tree);
  assert.equal(withoutPermission.flatMap(item => item.pages).some(page => page.id === 'users'), false);
});

test('security and registration settings share one user-management sidebar entry with direct tab routes', () => {
  const settingsAccess = [
    'platform:security:access',
    'platform:profile-fields:access',
    'platform:authentication:access',
    'platform:passkey:access',
    'platform:wechat:access',
    'platform:captcha:access',
    'platform:registration:access',
  ];
  const groups = collapseTabbedNavigation(availableNavigation(settingsAccess,["profile-console","authentication-console"],settingsAccess));
  const userManagement = groups.find(group => group.id === 'user-management');
  const settingRoutes = userManagement.pages.map(page => page.id);

  assert.ok(settingRoutes.includes('security'));
  assert.ok(settingRoutes.includes('profile-fields'));
  for (const tab of ['authentication', 'passkey', 'wechat', 'captcha', 'registration']) {
    assert.ok(!settingRoutes.includes(tab), `${tab} should be a tab, not a sidebar entry`);
    assert.equal(navigationPageIdForRoute(tab, groups), 'security');
    assert.ok(isSection(tab), `${tab} deep link remains a registered route`);
  }

  const serverOwnedSubset = mergeRuntimeNavigation(availableNavigation(settingsAccess,["profile-console","authentication-console"],settingsAccess), [
    { id: 'user-management', menuCode: 'user-management', menuType: 'CATALOG', sectionKey: null, children: [
      { id: 'security', menuCode: 'security', menuType: 'PAGE', sectionKey: 'security', path: '/dashboard/security', children: [] },
      { id: 'passkey', menuCode: 'passkey', menuType: 'PAGE', sectionKey: 'passkey', path: '/dashboard/passkey', children: [] },
    ] },
  ]);
  const subsetNavigation = collapseTabbedNavigation(serverOwnedSubset);
  assert.deepEqual(subsetNavigation.find(group => group.id === 'user-management').pages.map(page => page.id), ['security']);
  for (const tab of ['authentication', 'passkey', 'wechat', 'captcha', 'registration']) {
    assert.equal(navigationPageIdForRoute(tab, subsetNavigation), 'security');
  }

  const settingLabels = {
    security: '认证与安全',
    authentication: '认证方式',
    passkey: '通行密钥设置',
    wechat: '微信登录设置',
    captcha: '人机验证设置',
    registration: '公开注册设置',
  };
  const settingsTree = [{ id: 'user-management', menuCode: 'user-management', menuType: 'CATALOG', sectionKey: null, labelDefault: '用户与权限', children: Object.entries(settingLabels).map(([id, label]) => ({
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
    assert.deepEqual(runtimeBreadcrumbLabels(trail, '备用标题', node => node.labelDefault), ['用户与权限', title]);
  }
});

test('five tab groups derive labels, aliases, and sidebar anchors from module manifests', () => {
  assert.deepEqual(frontendTabGroups.map(({ moduleId, id, routes, anchor, collapse }) => ({ moduleId, id, routes, anchor, collapse })), [
    { moduleId: 'directory', id: 'user-activity', routes: ['users', 'online-users'], anchor: 'users', collapse: 'when-all-visible' },
    { moduleId: 'authentication-console', id: 'authentication-security', routes: ['security', 'authentication', 'passkey', 'wechat', 'captcha', 'registration'], anchor: 'security', collapse: 'when-anchor-visible' },
    { moduleId: 'delivery-settings', id: 'message-channels', routes: ['smtp', 'sms'], anchor: 'smtp', collapse: 'when-all-visible' },
    { moduleId: 'site-console', id: 'personalization', routes: ['branding', 'watermark', 'agreements', 'maintenance'], anchor: 'branding', collapse: 'when-all-visible' },
    { moduleId: 'delivery-console', id: 'delivery-records', routes: ['mail-deliveries', 'sms-deliveries'], anchor: 'mail-deliveries', collapse: 'when-all-visible' },
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
  const site = frontendModuleManifests.find(candidate => candidate.id === 'site-console');
  const partial = routeTabsForGroup(site, 'personalization', { branding: 'branding', watermark: 'watermark' });
  assert.deepEqual(partial.map(tab => tab.key), ['branding', 'watermark']);
});

test('sidebar aliases collapse within their assigned groups when every declared route is present', () => {
  const makeGroup = (id, pages) => ({ id, label: id, subgroup: null, sections: [], pages: pages.map(pageId => ({ id: pageId, label: pageId, permission: null })) });
  const complete = [
    makeGroup('administration', ['branding', 'watermark', 'agreements', 'maintenance']),
    makeGroup('communication', ['smtp', 'sms']),
    makeGroup('user-management', ['security', 'authentication', 'passkey', 'wechat', 'captcha', 'registration']),
  ];
  const collapsed = collapseTabbedNavigation(complete);
  assert.deepEqual(collapsed.map(group => [group.id, group.pages.map(page => page.id)]), [
    ['administration', ['branding']],
    ['communication', ['smtp']],
    ['user-management', ['security']],
  ]);
  assert.equal(navigationPageIdForRoute('watermark', collapsed), 'branding');

  const partial = [
    makeGroup('administration', ['branding', 'watermark', 'maintenance']),
    makeGroup('communication', ['smtp']),
    makeGroup('user-management', ['security', 'passkey']),
  ];
  const visible = collapseTabbedNavigation(partial);
  assert.deepEqual(visible[0].pages.map(page => page.id), ['branding', 'watermark', 'maintenance']);
  assert.deepEqual(visible[1].pages.map(page => page.id), ['smtp']);
  assert.deepEqual(visible[2].pages.map(page => page.id), ['security']);
});

test('audit navigation requires both module capability and permission', () => {
 const pages=(grants,modules)=>availableNavigation(grants,modules).flatMap(g=>g.pages.map(p=>p.id));
 assert.equal(pages(['platform:audit:access'],[]).includes('audit'),false);
 assert.equal(pages([],['audit-console']).includes('audit'),false);
 assert.equal(pages(['platform:audit:access'],['audit-console']).includes('audit'),true);
});

test('retired standalone AI pages are absent while model connections remain routable', () => {
  assert.equal(isSection('ai-capabilities'), false);
  assert.equal(isSection('ai-judgment'), false);
  assert.equal(isSection('ai-models'), true);
  const pages = availableNavigation(['platform:ai-models:access'], ['ai'])
    .flatMap(group => group.pages.map(page => page.id));
  assert.ok(pages.includes('ai-models'));
  assert.ok(!pages.includes('ai-capabilities'));
  assert.ok(!pages.includes('ai-judgment'));
});


test('directory business routes require the optional plugin to be enabled', () => {
  for (const section of ['users', 'roles', 'organizations', 'online-users', 'print-templates']) {
    const permission = `platform:${section}:access`;
    assert.equal(canAccessSection(section, [permission], [], [permission]), false);
    assert.equal(canAccessSection(section, [permission], ['directory'], [permission]), true);
    assert.equal(canAccessSection(section, [], ['directory'], []), false);
  }
});


test('authentication administration withdraws with its plugin while account routes remain available', () => {
  const rights=['platform:security:access','platform:authentication:access','platform:passkey:access','platform:wechat:access','platform:captcha:access','platform:registration:access'];
  for(const route of ['security','authentication','passkey','wechat','captcha','registration']) {
    assert.equal(canAccessSection(route,rights,[],rights),false);
    assert.equal(canAccessSection(route,rights,['authentication-console'],rights),true);
  }
  assert.equal(canAccessSection('account-authentication-security',[],[],[]),true);
  assert.equal(canAccessSection('account-security',[],[],[]),true);
});


test('personal file workflow is optional while required storage stays registered', () => {
  assert.equal(canAccessSection('files', [], []), false);
  assert.equal(canAccessSection('files', [], ['personal-files']), true);
  assert.equal(frontendModuleManifests.find(module => module.routes.some(route => route.id === 'files')).id, 'personal-files');
});
