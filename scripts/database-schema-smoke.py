"""Verify the single PostgreSQL baseline and its maintenance invariants."""
import json, os, re, subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
config_path = Path(os.environ.get('LUMIRA_CONFIG_FILE', str(root / '.runtime/local.json')))
if not config_path.is_absolute():
    config_path = root / config_path
config = json.loads(config_path.read_text())
url = config['LUMIRA_DB_URL'].replace('jdbc:', '', 1)
from urllib.parse import urlparse
parsed = urlparse(url)
database = parsed.path.lstrip('/') or 'lumira_next'
host = parsed.hostname or '127.0.0.1'
port = str(parsed.port or 5432)
user = config['LUMIRA_DB_USER']
psql = os.environ.get('PSQL_BIN', 'psql')

def sql(statement):
    result = subprocess.run(
        [psql, '-X', '-A', '-t', '-h', host, '-p', port, '-U', user, '-d', database,
        '-v', 'ON_ERROR_STOP=1', '-c', statement],
        capture_output=True, text=True, env={**os.environ, 'PGPASSWORD': config['LUMIRA_DB_PASSWORD']}, timeout=20)
    assert result.returncode == 0, result.stderr
    return result.stdout.strip()

migration_dir = root / 'backend/src/main/resources/db/migration'
upgrade_dir = root / 'backend/src/main/resources/db/upgrade'
active = sorted(migration_dir.glob('*.sql'))
active_names = {p.name for p in active}
ownership = json.loads((root / 'backend/src/main/resources/db/module-ownership.json').read_text())
migration_owners = ownership.get('migrationOwners', {})
baseline_name = ownership['baseline']
baseline_files = {name for name in active_names if name.startswith('B')}
assert baseline_name == 'B52__platform_initialization.sql'
assert re.fullmatch(r'B[0-9]+__[a-z0-9]+(?:_[a-z0-9]+)*\.sql', baseline_name)
assert baseline_files == {baseline_name}, baseline_files ^ {baseline_name}
assert active_names == {baseline_name}, active_names ^ {baseline_name}
assert not upgrade_dir.exists()
assert 'upgradeBaseline' not in ownership
assert migration_owners == {}
canonical = (migration_dir / baseline_name).read_text()
assert 'CREATE TABLE public.platform_navigation_menu' in canonical
assert 'CREATE TABLE public.platform_sms_delivery' in canonical
assert 'CREATE FUNCTION public.platform_visible_users' in canonical
assert 'CREATE FUNCTION public.platform_visible_organizations' in canonical

def baseline_data_section(table_name):
    marker = f'-- Data for Name: {table_name};'
    start = canonical.find(marker)
    assert start >= 0, f'missing B52 data section for {table_name}'
    end = canonical.find('-- Data for Name:', start + len(marker))
    return canonical[start:] if end < 0 else canonical[start:end]

page_source = (root / 'framework/platform-contracts/src/main/java/com/lumira/platform/common/PlatformPageId.java').read_text()
page_ids = dict(re.findall(r'^\s*([A-Z][A-Z0-9_]*)\("([^"]+)"', page_source, re.MULTILINE))
operation_source = (root / 'framework/platform-contracts/src/main/java/com/lumira/platform/common/PlatformOperation.java').read_text()
operation_permissions = {
    name: f'platform:{page_ids[page]}:{action}'
    for name, page, action in re.findall(
        r'^\s*([A-Z][A-Z0-9_]*)\(PlatformPageId\.([A-Z][A-Z0-9_]*),\s*"([^"]+)"',
        operation_source,
        re.MULTILINE,
    )
}
catalog_source = (root / 'backend/src/main/java/com/lumira/platform/roles/PlatformPermissionCatalog.java').read_text()
catalog_pages = set(re.findall(r'\bpage\(\s*PlatformPageId\.([A-Z][A-Z0-9_]*)', catalog_source))
catalog_operations = set(re.findall(r'PlatformOperation\.([A-Z][A-Z0-9_]*)', catalog_source))
assert catalog_pages and catalog_operations
assert catalog_pages <= page_ids.keys(), catalog_pages - page_ids.keys()
assert catalog_operations <= operation_permissions.keys(), catalog_operations - operation_permissions.keys()
expected_admin_permissions = {
    *(f'platform:{page_ids[page]}:access' for page in catalog_pages),
    *(operation_permissions[operation] for operation in catalog_operations),
}
plugin_manifests = sorted((root / 'plugins').glob('*/plugin.json'))
for manifest_path in plugin_manifests:
    expected_admin_permissions.update(json.loads(manifest_path.read_text()).get('permissions', []))

role_adapter = (root / 'backend/src/main/java/com/lumira/platform/roles/RegistrationRoleAdapter.java').read_text()
allowlist_match = re.search(r'ALLOWED_AUTHORITIES\s*=\s*Set\.of\(([\s\S]*?)\);', role_adapter)
assert allowlist_match, 'registration role allowlist must remain explicit'
expected_user_permissions = set(re.findall(r'"([^"]+)"', allowlist_match.group(1)))
assert expected_user_permissions == {
    'platform:feedback:access', 'platform:feedback:read', 'platform:feedback:create'
}, expected_user_permissions

admin_role_id = '00000000-0000-0000-0000-000000000052'
user_role_id = '00000000-0000-0000-0000-000000000053'
role_seed = baseline_data_section('platform_role')
role_seed_rows = set(re.findall(r"\('([0-9a-f-]{36})', '([^']+)', '([^']+)', true, 0, '(?:ALL|SELF)'", role_seed))
assert role_seed_rows == {
    (admin_role_id, 'admin', '管理员'),
    (user_role_id, 'user', '普通用户'),
}, role_seed_rows
registration_seed = baseline_data_section('platform_registration_policy')
assert re.search(
    rf"INSERT INTO public\.platform_registration_policy VALUES \(1, 1, false, '\{{\}}', '{user_role_id}'",
    registration_seed,
), 'B52 must keep registration disabled and select the ordinary role by default'
authority_seed = baseline_data_section('platform_role_authority')
admin_seed_permissions = set(
    re.findall(rf"\('{admin_role_id}', '([^']+)'\)", authority_seed)
)
user_seed_permissions = set(
    re.findall(rf"\('{user_role_id}', '([^']+)'\)", authority_seed)
)
assert admin_seed_permissions == expected_admin_permissions, (
    expected_admin_permissions - admin_seed_permissions,
    admin_seed_permissions - expected_admin_permissions,
)
assert user_seed_permissions == expected_user_permissions, (
    expected_user_permissions - user_seed_permissions,
    user_seed_permissions - expected_user_permissions,
)
assert not any('*' in permission for permission in admin_seed_permissions)
for table_name in ('platform_user', 'platform_user_role', 'platform_authority'):
    assert not re.search(
        rf'^\s*INSERT INTO public\.{table_name}\b',
        baseline_data_section(table_name),
        re.MULTILINE,
    ), f'B52 must not seed accounts or direct authorities in {table_name}'

required_default_roles = {
    f'admin|{admin_role_id}',
    f'user|{user_role_id}',
}
actual_default_roles = set(filter(None, sql(
    "SELECT code || '|' || id::text FROM platform_role "
    "WHERE id IN ('00000000-0000-0000-0000-000000000052',"
    "'00000000-0000-0000-0000-000000000053') ORDER BY code"
).splitlines()))
assert actual_default_roles == required_default_roles, (required_default_roles, actual_default_roles)
admin_account_count = int(sql(
    "SELECT count(*) FROM platform_user WHERE lower(username)='admin'"
))
if admin_account_count:
    assert int(sql(
        "SELECT count(*) FROM platform_user_role assigned "
        "JOIN platform_user account ON account.id=assigned.user_id "
        "WHERE lower(account.username)='admin' AND assigned.role_id="
        "'00000000-0000-0000-0000-000000000052'"
    )) > 0, 'the bootstrap admin account must use the administrator role'
    assert int(sql(
        "SELECT count(*) FROM platform_authority direct "
        "JOIN platform_user account ON account.id=direct.user_id "
        "WHERE lower(account.username)='admin'"
    )) == 0, 'the bootstrap admin account must not hold direct authority grants'

expected_tables = set(ownership['tableOwners'])
actual_tables = set(filter(None, sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'platform_%' ORDER BY tablename").splitlines()))
assert actual_tables == expected_tables, (expected_tables - actual_tables, actual_tables - expected_tables)
expected_builtin_sections = {
    'home', 'files', 'modules', 'monitoring', 'users', 'online-users', 'roles',
    'organizations', 'dictionaries', 'files-all', 'notifications', 'branding',
    'smtp', 'sms', 'profile-fields', 'agreements', 'watermark', 'maintenance',
    'security', 'passkey', 'audit', 'mail-deliveries', 'sms-deliveries',
    'event-outbox', 'feedback', 'navigation', 'sensitive-words', 'localization',
    'ai-console', 'ai-models', 'api-docs', 'captcha', 'authentication', 'wechat',
    'registration', 'print-templates',
}
actual_builtin_sections = set(filter(None, sql("SELECT section_key FROM platform_navigation_menu WHERE built_in AND NOT deleted AND menu_type='PAGE' ORDER BY section_key").splitlines()))
assert actual_builtin_sections == expected_builtin_sections, (expected_builtin_sections - actual_builtin_sections, actual_builtin_sections - expected_builtin_sections)
expected_builtin_catalogs = {
    'overview', 'user-management', 'content-management', 'operations',
    'administration', 'communication', 'payments', 'ai-management',
}
actual_builtin_catalogs = set(filter(None, sql("SELECT menu_code FROM platform_navigation_menu WHERE built_in AND NOT deleted AND menu_type='CATALOG' ORDER BY menu_code").splitlines()))
assert actual_builtin_catalogs == expected_builtin_catalogs, (expected_builtin_catalogs - actual_builtin_catalogs, actual_builtin_catalogs - expected_builtin_catalogs)
assert len(expected_builtin_sections) == 36
assert len(expected_builtin_catalogs) == 8
assert int(sql("SELECT count(*) FROM platform_navigation_menu WHERE built_in AND NOT deleted AND menu_type='PAGE'")) == 36
assert int(sql("SELECT count(*) FROM platform_navigation_menu WHERE built_in AND NOT deleted AND menu_type='CATALOG'")) == 8
assert int(sql("SELECT count(*) FROM platform_navigation_menu WHERE built_in AND NOT deleted")) == 44
assert int(sql("SELECT count(*) FROM platform_navigation_menu WHERE built_in AND NOT deleted AND menu_type NOT IN ('PAGE','CATALOG')")) == 0
assert int(sql("SELECT count(*) FROM platform_dictionary_item")) >= 18
assert int(sql("SELECT count(*) FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='work_order_feedback_category' AND d.enabled AND i.enabled AND i.value IN ('QUESTION','BUG','FEATURE','ACCOUNT','OTHER')")) == 5
assert int(sql("SELECT count(*) FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category' AND i.value IN ('DEFAULT','IMPORTED')")) == 2
assert int(sql("SELECT count(*) FROM (SELECT DISTINCT w.category AS value FROM platform_sensitive_word w WHERE w.deleted=false EXCEPT SELECT i.value FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category') seeded_categories")) == 0
assert int(sql("SELECT count(*) FROM platform_localization_locale WHERE enabled AND built_in AND locale_code IN ('zh-CN','zh-TW','en','ja','ko','fr','es','de')")) == 8
assert int(sql("SELECT count(*) FROM platform_localization_locale WHERE enabled AND (source_locale NOT IN (SELECT locale_code FROM platform_localization_locale WHERE enabled) OR fallback_locale NOT IN (SELECT locale_code FROM platform_localization_locale WHERE enabled))")) == 0
# New imported plugins may add persistent state rows; the upgrade seeds must remain.
assert {'audit-console','feedback','localization','sensitive-words'} <= set(sql("SELECT module_id FROM platform_module_state").splitlines())
assert int(sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname IN ('platform_visible_users','platform_visible_organizations')")) == 2
assert int(sql("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN ('platform_navigation_page_path_uq','platform_navigation_page_section_uq','platform_sms_pending','platform_sms_leases','platform_module_package_active_uq','platform_module_package_lookup','platform_passkey_challenge_expiry','platform_passkey_credential_user','platform_feedback_category_recent','platform_sensitive_word_category_recent')")) == 10
assert int(sql("SELECT count(*) FROM pg_constraint WHERE conname IN ('platform_file_orphan_owner_consistency','platform_event_outbox_check','platform_sms_delivery_check','platform_navigation_shape_ck','platform_event_replay_approval_reason_check','platform_module_package_artifact_ref_check','platform_module_package_status_check','platform_module_package_identity_uq','platform_passkey_challenge_kind_check','platform_passkey_credential_sign_count_check','platform_feedback_category_check')")) == 11
assert 'https' in sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='platform_module_package_artifact_ref_check'")
assert int(sql("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name='platform_user' AND column_name='password_hash'")) == 256
assert int(sql("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name='platform_feedback' AND column_name='category'")) == 100
print('PASS: single B52 PostgreSQL baseline, feedback category contract, platform tables, navigation entries, query indexes and invariant constraints')
