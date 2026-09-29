import test from 'node:test';
import assert from 'node:assert/strict';
import { defaultPreferences, parsePreferences, serializePreferences } from '../apps/web/src/platform/preferences/model.ts';

test('untrusted preference cookies safely recover from malformed and unexpected values', () => {
  for (const raw of [undefined, '', '%', 'null', '[]', 'true', '"dark"']) {
    assert.deepEqual(parsePreferences(raw), defaultPreferences);
  }
  // Only locale syntax is validated here; registration is checked against the runtime registry.
  assert.deepEqual(parsePreferences(JSON.stringify({dark:'true',locale:'not a locale!',navigation:'external',labels:0,pinned:null})), defaultPreferences);
  assert.equal(parsePreferences(JSON.stringify({locale:'invalid'})).locale, 'invalid');
});
test('cookie roundtrip preserves theme and locale while ignoring retired navigation fields', () => {
  for (const locale of ['zh-CN','zh-TW','en','ja','ko','fr','es','de']) {
    const value = {dark:true,locale};
    assert.deepEqual(parsePreferences(serializePreferences(value)),value);
    assert.deepEqual(
      parsePreferences(encodeURIComponent(JSON.stringify({dark:false,locale,navigation:'dual',labels:true,pinned:false}))),
      {dark:false,locale},
    );
  }
});
