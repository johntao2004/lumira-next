import test from 'node:test';
import assert from 'node:assert/strict';
import { safeReturnPath } from '../apps/web/src/platform/auth/return-path.ts';
test('restores dashboard location without allowing external redirects', () => {
  assert.equal(safeReturnPath('/dashboard/users?page=2#row'), '/dashboard/users?page=2#row');
  assert.equal(safeReturnPath('/dashboard/custom-page'), '/dashboard/custom-page');
  for (const path of [null, '//evil.test', 'https://evil.test/dashboard/home', '/\\evil.test', '/user/login', '/dashboard/home/extra', '/dashboard/Bad', 'javascript:alert(1)']) assert.equal(safeReturnPath(path), '/dashboard/home');
});
