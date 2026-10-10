import test from 'node:test';
import assert from 'node:assert/strict';
import { createAccountLocaleSync } from '../apps/web/src/platform/preferences/account-locale-sync.ts';

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
};

test('anonymous choices remain local and account reads do not write preferences', async () => {
  const applied = [], saved = [];
  const sync = createAccountLocaleSync({
    load: async () => ({ locale: 'ja' }),
    save: async (locale) => { saved.push(locale); return { locale }; },
    apply: (locale) => applied.push(locale),
  });
  await sync.select('en');
  await sync.setAccount('alice');
  assert.deepEqual(applied, ['en', 'ja']);
  assert.deepEqual(saved, []);
  sync.dispose();
});

test('a late account read cannot override a newer language selection', async () => {
  const read = deferred(), applied = [], saved = [];
  const sync = createAccountLocaleSync({
    load: () => read.promise,
    save: async (locale) => { saved.push(locale); return { locale }; },
    apply: (locale) => applied.push(locale),
  });
  const loading = sync.setAccount('alice');
  await sync.select('de');
  read.resolve({ locale: 'ja' });
  await loading;
  assert.deepEqual(applied, ['de']);
  assert.deepEqual(saved, ['de']);
});

test('rapid changes persist in selection order, while failures keep the local choice', async () => {
  const first = deferred(), saved = [], applied = [];
  const sync = createAccountLocaleSync({
    load: async () => ({ locale: null }),
    save: async (locale) => {
      saved.push(locale);
      if (locale === 'ja') await first.promise;
      if (locale === 'fr') throw new Error('offline');
      return { locale };
    },
    apply: (locale) => applied.push(locale),
  });
  await sync.setAccount('alice');
  const a = sync.select('ja'), b = sync.select('en');
  await new Promise(setImmediate);
  assert.deepEqual(saved, ['ja']);
  first.resolve();
  await Promise.all([a, b]);
  assert.deepEqual(saved, ['ja', 'en']);
  await assert.rejects(sync.select('fr'), /offline/);
  assert.equal(applied.at(-1), 'fr');
  await sync.select('de');
  assert.equal(saved.at(-1), 'de');
});

test('account switches discard stale reads and queued writes, including effect remounts', async () => {
  const read = deferred(), writing = deferred(), saved = [], applied = [];
  let reads = 0;
  const sync = createAccountLocaleSync({
    load: async () => ++reads === 1 ? read.promise : { locale: 'ko' },
    save: async (locale) => { saved.push(locale); await writing.promise; return { locale }; },
    apply: (locale) => applied.push(locale),
  });
  const a = sync.setAccount('alice');
  const first = sync.select('en'), queued = sync.select('fr');
  await new Promise(setImmediate);
  await sync.setAccount('bob');
  read.resolve({ locale: 'ja' });
  writing.resolve();
  await Promise.all([a, first, queued]);
  assert.deepEqual(saved, ['en']);
  assert.equal(applied.at(-1), 'ko');
  sync.dispose();
  await sync.setAccount('bob');
  assert.equal(reads, 3);
});
