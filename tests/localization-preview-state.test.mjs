import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isCurrentLocalizationBundlePreview,
  mergeLocalizationAiResults,
  sameLocalizationAiScope,
} from '../plugins/localization/frontend/localization-preview-state.ts';

const aiScope = overrides => ({
  generation: 4,
  sourceLocale: 'en',
  targetLocale: 'zh-CN',
  providerId: 'provider-a',
  modelName: 'model-a',
  providerVersion: null,
  sourceBundleVersion: 12,
  sourceSnapshotToken: 'a'.repeat(64),
  overwriteManual: false,
  scope: 'page',
  ...overrides,
});

test('AI preview scope binds languages, provider/model, bundle version, overwrite and generation', () => {
  const captured = aiScope();
  assert.ok(sameLocalizationAiScope(captured, aiScope()));
  for (const override of [
    { sourceLocale: 'zh-CN' }, { targetLocale: 'en' }, { providerId: 'provider-b' },
    { modelName: 'model-b' }, { sourceBundleVersion: 13 }, { sourceSnapshotToken: 'b'.repeat(64) }, { overwriteManual: true },
    { generation: 5 },
  ]) assert.equal(sameLocalizationAiScope(captured, aiScope(override)), false);
});

test('late AI generations cannot replace results and failed retries keep same-version successes', () => {
  const successful = { entry: { version: 7 }, translation: '已翻译' };
  const current = { hello: successful };
  assert.equal(mergeLocalizationAiResults(current, { hello: { entry: { version: 7 }, errorCode: 'failed' } }, 3, 4), current);
  assert.deepEqual(mergeLocalizationAiResults(current, { hello: { entry: { version: 7 }, errorCode: 'failed' } }, 4, 4), current);
  assert.deepEqual(mergeLocalizationAiResults(current, { hello: { entry: { version: 8 }, errorCode: 'stale' } }, 4, 4), {
    hello: { entry: { version: 8 }, errorCode: 'stale' },
  });
});

test('bundle import preview is valid only for the same file object, overwrite mode and generation', () => {
  const bundle = { locale: 'fr', messages: { hello: 'bonjour' } };
  const preview = { generation: 9, bundle, overwrite: true, result: { added: 1 } };
  assert.ok(isCurrentLocalizationBundlePreview(preview, { generation: 9, bundle, overwrite: true }));
  assert.equal(isCurrentLocalizationBundlePreview(preview, { generation: 10, bundle, overwrite: true }), false);
  assert.equal(isCurrentLocalizationBundlePreview(preview, { generation: 9, bundle: { ...bundle }, overwrite: true }), false);
  assert.equal(isCurrentLocalizationBundlePreview(preview, { generation: 9, bundle, overwrite: false }), false);
});
