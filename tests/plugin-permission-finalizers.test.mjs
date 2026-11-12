import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';

const plugins = [
  {
    id: 'feedback',
    version: '1.0.15',
    backend: 'plugins/feedback/backend/src/main/java/com/lumira/platform/feedback/FeedbackModuleConfiguration.java',
    migration: 'plugins/feedback/migrations/V4__plugin_initialization.sql',
  },
  {
    id: 'localization',
    version: '1.0.13',
    backend: 'plugins/localization/backend/src/main/java/com/lumira/platform/localization/LocalizationModuleConfiguration.java',
    migration: 'plugins/localization/migrations/V3__plugin_initialization.sql',
  },
  {
    id: 'sensitive-words',
    version: '1.0.11',
    backend: 'plugins/sensitive-words/backend/src/main/java/com/lumira/platform/sensitive/SensitiveWordsModuleConfiguration.java',
    migration: 'plugins/sensitive-words/migrations/V3__plugin_initialization.sql',
  },
  {
    id: 'mfa',
    version: '1.0.11',
    backend: 'plugins/mfa/backend/src/main/java/com/lumira/platform/mfa/MfaModuleConfiguration.java',
    migration: 'plugins/mfa/migrations/V7__plugin_initialization.sql',
  },
  {
    id: 'payment-core',
    version: '1.1.0',
    backend: 'plugins/payment-core/backend/src/main/java/com/lumira/plugins/paymentcore/PaymentCoreModuleConfiguration.java',
    migration: 'plugins/payment-core/migrations/V5__plugin_initialization.sql',
  },
  {
    id: 'wechat-pay',
    version: '1.1.0',
    backend: 'plugins/wechat-pay/backend/src/main/java/com/lumira/plugins/wechatpay/WechatPayPlugin.java',
    migration: 'plugins/wechat-pay/migrations/V2__plugin_initialization.sql',
  },
  {
    id: 'alipay-pay',
    version: '1.1.0',
    backend: 'plugins/alipay-pay/backend/src/main/java/com/lumira/plugins/alipaypay/AlipayModuleConfiguration.java',
    migration: 'plugins/alipay-pay/migrations/V2__plugin_initialization.sql',
  },
];

function read(path) {
  return readFileSync(path, 'utf8');
}

test('plugin initialization keeps one final SQL and the published migration identities', () => {
  for (const plugin of plugins) {
    assert.ok(existsSync(plugin.migration), `${plugin.id} has final-state initialization`);
    const sql = read(plugin.migration);
    assert.match(sql, /(?:PERFORM|SELECT) public\.platform_normalize_legacy_permission_projections\(\)/);
    const directory = `plugins/${plugin.id}/migrations`;
    const catalog = JSON.parse(read(`${directory}/initialization.json`));
    assert.equal(catalog.pluginId, plugin.id);
    assert.ok([1, 2].includes(catalog.formatVersion));
    assert.equal(`${directory}/${catalog.script}`, plugin.migration);
    assert.deepEqual(readdirSync(directory).filter(name => name.endsWith('.sql')), [catalog.script]);
    assert.equal(catalog.publishedMigrations.length + (catalog.evolutions?.length ?? 0), Number(catalog.version));
    catalog.publishedMigrations.forEach((entry, index) => {
      assert.equal(entry.version, String(index + 1));
      assert.match(entry.script, new RegExp(`^V${entry.version}__[a-z0-9_]+\\.sql$`));
      assert.ok(Number.isInteger(entry.checksum));
      assert.ok(entry.description.length > 0);
      assert.equal(existsSync(`${directory}/${entry.script}`), false);
    });

    const manifest = JSON.parse(read(`plugins/${plugin.id}/plugin.json`));
    const frontend = read(`plugins/${plugin.id}/frontend/manifest.ts`);
    const backend = read(plugin.backend);
    assert.equal(manifest.version, plugin.version, `${plugin.id} package version`);
    assert.match(frontend, new RegExp(`version: \\"${plugin.version}\\"`), `${plugin.id} frontend version`);
    assert.ok(backend.includes(`"${plugin.version}"`), `${plugin.id} backend descriptor version`);
  }

  for (const id of ['wechat-pay', 'alipay-pay']) {
    const manifest = JSON.parse(read(`plugins/${id}/plugin.json`));
    assert.equal(
      manifest.dependencies.find(({ id: dependencyId }) => dependencyId === 'payment-core')?.version,
      '1.1.0',
      `${id} pins the finalized payment-core package`,
    );
  }
});

test('MFA finalizer preserves legacy reset as the V50 global AND target-scope components', () => {
  const sql = read('plugins/mfa/migrations/V7__plugin_initialization.sql');

  assert.match(sql, /'platform:mfa:read'.*'platform:mfa:management-access'/s);
  assert.match(sql, /'platform:mfa:read'.*'platform:mfa:management-read'/s);
  assert.match(sql, /'platform:mfa:manage'.*'platform:mfa:management-access'/s);
  assert.match(sql, /'platform:mfa:manage'.*'platform:mfa:management-read'/s);
  assert.match(sql, /'platform:mfa:reset'.*'platform:__v50:mfa-reset-global'/s);
  assert.match(sql, /'platform:__v50:mfa-user-update-scope'/);
  assert.match(sql, /reset_role\.enabled\s+AND\s+reset_role\.data_scope = 'ALL'/);
  assert.match(sql, /direct_reset\.authority = 'platform:mfa:reset'/);
  assert.match(sql, /update_role\.enabled/);
  assert.match(sql, /update_role\.data_scope = 'ALL'/);
  assert.match(sql, /same_role_reset\.authority = 'platform:mfa:reset'/);
  assert.doesNotMatch(sql, /DELETE\s+FROM\s+platform_(?:role_)?authority[\s\S]*platform:users:update/i);
});

test('post-V51 installs keep published permission-only migrations inert by exact version', () => {
  const runner = read('backend/src/main/java/com/lumira/platform/plugins/PluginPermissionMigrationRunner.java');

  for (const id of ['feedback', 'localization', 'sensitive-words']) {
    assert.match(
      runner,
      new RegExp(`"${id}"[\\s\\S]*?new LegacyPermissionMigration\\(\\s*"2", "1", "V2__independent_page_operations\\.sql", true\\)`),
    );
  }
  assert.match(
    runner,
    /"mfa"[\s\S]*?new LegacyPermissionMigration\(\s*"6", "5", "V6__fine_grained_admin_permissions\.sql", false\)/,
  );
  assert.match(runner, /shouldUseNoOp\(pluginId, true, hasAppliedVersionedMigration\)/);
  assert.match(runner, /!policy\.onlyForFreshInstall\(\) \|\| !hasAppliedVersionedMigration/);
  assert.match(runner, /new ResolvedMigrationImpl\([\s\S]*?source\.getChecksum\(\)[\s\S]*?source\.getType\(\)/);
  assert.match(runner, /Integer sourceChecksum = selected\.getChecksum\(\)/);
  assert.match(runner, /var sourceType = selected\.getType\(\)/);
  assert.match(runner, /skipDefaultResolvers\(true\)/);
  assert.match(runner, /standard\.migrate\(\);/);

  for (const [id, retired, current] of [
    ['feedback', 'platform:feedback:manage', 'platform:feedback:update'],
    ['localization', 'platform:localization:write', 'platform:localization:update'],
    ['sensitive-words', 'platform:sensitive-words:write', 'platform:sensitive-words:update'],
  ]) {
    const catalog = JSON.parse(read(`plugins/${id}/migrations/initialization.json`));
    const sql = read(`plugins/${id}/migrations/${catalog.script}`);
    assert.ok(sql.includes(`'${retired}'`), `${id} finalizer handles its distinct obsolete aggregate`);
    assert.ok(sql.includes(`'${current}'`), `${id} preserves current independent operation codes`);
  }
});

test('payment-core finalizer preserves provider grants before retiring shared payment authorities', () => {
  const sql = read('plugins/payment-core/migrations/V5__plugin_initialization.sql');
  const callsNormalizer = sql.indexOf('SELECT public.platform_normalize_legacy_permission_projections()');
  assert.ok(callsNormalizer > 0);

  for (const operation of ['orders-read', 'orders-create', 'orders-manage', 'orders-refund', 'settings-update']) {
    assert.ok(sql.includes(`platform:wechat-pay:${operation}`), `WeChat ${operation} legacy mapping`);
    assert.ok(sql.includes(`platform:alipay-pay:${operation}`), `Alipay ${operation} legacy mapping`);
  }

  assert.ok(sql.indexOf('INSERT INTO platform_role_authority') < callsNormalizer);
  assert.ok(sql.indexOf('INSERT INTO platform_authority') < callsNormalizer);
});
