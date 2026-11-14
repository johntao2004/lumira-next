import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

for (const id of ['localization', 'sensitive-words']) {
  const manifest = JSON.parse(readFileSync(new URL(`../plugins/${id}/plugin.json`, import.meta.url), 'utf8'));
  const access = `platform:${id}:access`;
  const readOnlyPosts = new Set([
    '/api/platform/sensitive-policies/preview',
    '/api/platform/sensitive-words/check',
  ]);
  test(`${id} page reads accept global access alone and mutations retain operation grants`, () => {
    const rules = manifest.apiRules.filter((rule) => !rule.publicAccess);
    for (const rule of rules) {
      assert.equal(rule.global, true, rule.path);
      assert.deepEqual(rule.authorities, [], rule.path);
      if (rule.method === 'GET' || readOnlyPosts.has(rule.path)) {
        assert.deepEqual(rule.allAuthorities, [access], rule.path);
        assert.equal(rule.pageReadOnly, true, rule.path);
      } else {
        assert.notEqual(rule.pageReadOnly, true, rule.path);
        assert.ok(rule.allAuthorities.includes(access), rule.path);
        assert.ok(rule.allAuthorities.some((permission) => permission !== access), rule.path);
      }
    }
  });
}
