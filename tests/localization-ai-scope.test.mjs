import test from 'node:test';
import assert from 'node:assert/strict';
import {loadTranslationCandidates} from '../plugins/localization/frontend/localization-ai-scope.ts';

const row = (key, state = 'MISSING', overrides = {}) => ({
  id: key, messageKey: key, enabled: true, overridden: false, valueSource: state, ...overrides,
});
const request = {page: 4, pageSize: 10, search: 'visible only', filters: {messageKey: 'visible'}, filterModes: {messageKey: 'contains'}};
const options = (scope, extra = {}) => ({scope, request, selectedKeys: [], onlyMissing: true, signal: new AbortController().signal, ...extra});

test('all untranslated scope crosses every page and ignores table search and selection', async () => {
  const calls = [];
  const pages = [
    [row('first'), row('manual', 'FALLBACK', {overridden: true}), row('disabled', 'MISSING', {enabled: false})],
    [row('second', 'FALLBACK'), row('translated', 'STATIC'), row('source', 'SOURCE')],
    [row('third'), row('first')],
  ];
  const candidates = await loadTranslationCandidates(options('all-missing', {onlyMissing: false, selectedKeys: ['manual']}), async req => {
    calls.push(req);
    return {items: pages[req.page - 1], total: 250, page: req.page, pageSize: 100};
  });
  assert.deepEqual(candidates.map(item => item.messageKey), ['first', 'second', 'third']);
  assert.deepEqual(calls, [1, 2, 3].map(page => ({page, pageSize: 100, search: '', filters: {}, filterModes: {}})));
});

test('current page and selected keys retain table scope and optional missing filter', async () => {
  const rows = [row('missing'), row('fallback', 'FALLBACK'), row('translated', 'STATIC'), row('disabled', 'MISSING', {enabled: false})];
  const load = async req => {
    assert.deepEqual(req, request);
    return {items: rows, total: 1000, page: request.page, pageSize: request.pageSize};
  };
  assert.deepEqual((await loadTranslationCandidates(options('page'), load)).map(item => item.id), ['missing', 'fallback']);
  assert.deepEqual((await loadTranslationCandidates(options('selected', {onlyMissing: false, selectedKeys: ['translated', 'disabled']}), load)).map(item => item.id), ['translated']);
});

test('cancelling a whole-catalogue scan prevents further pages', async () => {
  const controller = new AbortController();
  let calls = 0;
  await assert.rejects(loadTranslationCandidates(options('all-missing', {signal: controller.signal}), async req => {
    calls++;
    controller.abort();
    return {items: [row('missing')], total: 200, page: req.page, pageSize: 100};
  }), {name: 'AbortError'});
  assert.equal(calls, 1);
});
