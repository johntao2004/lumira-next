import assert from 'node:assert/strict';
import test from 'node:test';
import { workspaceRouteState } from '../apps/web/src/platform/shell/workspace-route-state.ts';

const unknown = {
  registeredSection: false,
  accountSettingsPage: false,
  removedDraftsRoute: false,
  runtimeLoading: false,
  runtimePageExists: false,
  previewUnavailable: false,
  activeRouteAccess: null,
};

test('unknown dashboard paths are not authorization failures, including during role preview', () => {
  assert.deepEqual(workspaceRouteState(unknown), { unknownSection: true, denied: false });
  assert.deepEqual(workspaceRouteState({ ...unknown, previewUnavailable: true }), { unknownSection: true, denied: false });
});

test('known routes retain access, disabled-module and preview restrictions', () => {
  const known = { ...unknown, registeredSection: true, activeRouteAccess: true };
  assert.deepEqual(workspaceRouteState(known), { unknownSection: false, denied: false });
  assert.deepEqual(workspaceRouteState({ ...known, activeRouteAccess: false }), { unknownSection: false, denied: true });
  assert.deepEqual(workspaceRouteState({ ...known, previewUnavailable: true }), { unknownSection: false, denied: true });
  assert.deepEqual(workspaceRouteState({ ...known, runtimePageRoutable: false }), { unknownSection: false, denied: true });
});

test('runtime routes wait for navigation and preserve non-routable protection', () => {
  assert.deepEqual(workspaceRouteState({ ...unknown, runtimeLoading: true }), { unknownSection: false, denied: false });
  assert.deepEqual(workspaceRouteState({ ...unknown, runtimePageExists: true }), { unknownSection: false, denied: false });
  assert.deepEqual(workspaceRouteState({ ...unknown, runtimePageExists: true, runtimePageRoutable: false }), { unknownSection: false, denied: true });
});

test('account pages and the explicitly retired drafts page are not misclassified as unknown', () => {
  assert.equal(workspaceRouteState({ ...unknown, accountSettingsPage: true }).unknownSection, false);
  assert.equal(workspaceRouteState({ ...unknown, removedDraftsRoute: true }).unknownSection, false);
});
