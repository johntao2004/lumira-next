import test from 'node:test';
import assert from 'node:assert/strict';
import { syncMenuState } from '../apps/web/src/platform/shell/menu-state.ts';
import { availableNavigation, canAccessSection, collapseTabbedNavigation, navigationPageIdForRoute } from '../apps/web/src/platform/shell/routes.ts';

const groups = ['overview', 'content-management', 'administration'];

test('Tab/query changes preserve all manually opened groups and a manually closed active group', () => {
  const opened = { activeGroup: 'content-management', openGroups: [...groups] };
  assert.equal(syncMenuState(opened, groups, 'content-management'), opened);
  const closed = { activeGroup: 'content-management', openGroups: ['administration'] };
  assert.equal(syncMenuState(closed, groups, 'content-management'), closed);
});

test('navigating to another group reveals it without collapsing previous groups', () => {
  const state = syncMenuState({ activeGroup: 'content-management', openGroups: ['content-management'] }, groups, 'administration');
  assert.deepEqual(state.openGroups, ['content-management', 'administration']);
  assert.deepEqual(syncMenuState(state, groups, null).openGroups, state.openGroups);
});

test('permission/module changes prune unavailable groups without restoring forbidden groups', () => {
  const state = syncMenuState({ activeGroup: 'content-management', openGroups: [...groups] }, ['overview'], 'content-management');
  assert.deepEqual(state, { activeGroup: null, openGroups: ['overview'] });
});

test('every built-in Tab route in a navigation group preserves its expansion state', () => {
  const permissions = [
    'platform:branding:access', 'platform:watermark:access', 'platform:agreements:access', 'platform:maintenance:access',
    'platform:security:access', 'platform:passkey:access', 'platform:smtp:access', 'platform:sms:access',
    'platform:users:access', 'platform:online-users:access', 'platform:mail-deliveries:access', 'platform:sms-deliveries:access',
  ];
  const navigation = collapseTabbedNavigation(availableNavigation(permissions, ["delivery-console", "directory", "site-console", "delivery-settings", "authentication-console"], permissions));
  const ids = navigation.map(group => group.id);
  for (const routes of [
    ['branding', 'watermark', 'agreements', 'maintenance'],
    ['security', 'passkey'], ['smtp', 'sms'], ['users', 'online-users'], ['mail-deliveries', 'sms-deliveries'],
  ]) {
    const owners = routes.map(route => navigation.find(group => group.pages.some(page => page.id === navigationPageIdForRoute(route, navigation)))?.id);
    assert.ok(owners.every(Boolean), `navigation owner missing for ${routes}`);
    assert.equal(new Set(owners).size, 1);
    const state = { activeGroup: owners[0], openGroups: ids };
    for (const owner of owners) assert.equal(syncMenuState(state, ids, owner), state);
  }
});

test('file retention keeps its settings permission but is no longer a settings sidebar entry', () => {
  assert.equal(canAccessSection('file-retention', ['platform:file-retention:access'], ['file-retention']), true);
  assert.equal(canAccessSection('file-retention', []), false);
  assert.equal(canAccessSection('file-retention', ['platform:file-retention:access']), false);
  const navigation = availableNavigation(['platform:file-retention:access'], [], ['platform:file-retention:access']);
  assert.equal(navigation.flatMap(group => group.pages).some(page => page.id === 'file-retention'), false);
});
