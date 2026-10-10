import test from 'node:test';
import assert from 'node:assert/strict';
import { readTabAnchor, tabAnchorName, updateFragment } from '../apps/web/src/platform/routing/tab-anchor.ts';

test('refresh reads only available tab keys and falls back for invalid anchors', () => {
  assert.equal(readTabAnchor('#tab=trash', '', ['files', 'trash'], 'files'), 'trash');
  for (const hash of ['', '#tab=missing', '#tab=%E0%A4%A', '#tab=constructor']) {
    assert.equal(readTabAnchor(hash, '', ['files', 'trash'], 'files'), 'files');
  }
  assert.equal(readTabAnchor('#tab=roles', '', ['profile', 'organizations'], 'profile'), 'profile');
});

test('nested tab groups retain one another and unrelated fragment fields', () => {
  let hash = updateFragment('#tab=agreements&other=keep', { [tabAnchorName('user-agreement')]: 'preview' });
  hash = updateFragment(hash, { [tabAnchorName('privacy-agreement')]: 'edit' });
  hash = updateFragment(hash, { [tabAnchorName('notifications')]: 'read', inbox: 'open' });
  assert.equal(readTabAnchor(hash, '', ['branding', 'agreements'], 'branding'), 'agreements');
  assert.equal(readTabAnchor(hash, 'user-agreement', ['edit', 'preview'], 'edit'), 'preview');
  assert.equal(readTabAnchor(hash, 'privacy-agreement', ['edit', 'preview'], 'edit'), 'edit');
  assert.equal(readTabAnchor(hash, 'notifications', ['unread', 'read'], 'unread'), 'read');
  assert.equal(new URLSearchParams(hash.slice(1)).get('other'), 'keep');
});

test('closing overlays removes their visibility fields while preserving tab positions', () => {
  const hash = updateFragment('#tab=users&user=123&tab.user-editor=roles&inbox=open&tab.notifications=read', { user: null, inbox: null });
  assert.equal(hash, '#tab=users&tab.user-editor=roles&tab.notifications=read');
  assert.equal(updateFragment('#inbox=open', { inbox: null }), '');
});

test('fragment values are safely encoded and stable on repeated normalization', () => {
  const hash = updateFragment('', { [tabAnchorName('example')]: '用户 & 预览#1' });
  assert.equal(readTabAnchor(hash, 'example', ['用户 & 预览#1'], 'edit'), '用户 & 预览#1');
  assert.equal(updateFragment(hash, { [tabAnchorName('example')]: '用户 & 预览#1' }), hash);
});
