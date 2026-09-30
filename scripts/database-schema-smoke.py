"""Verify the single PostgreSQL baseline and its maintenance invariants."""
import json, os, subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
config = json.loads((root / '.runtime/local.json').read_text())
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
active = sorted(migration_dir.glob('*.sql'))
active_names = {p.name for p in active}
canonical = (migration_dir / 'V1__platform_initialization.sql').read_text()
ownership = json.loads((root / 'backend/src/main/resources/db/module-ownership.json').read_text())
assert ownership['migrationOwners']['V13__business_dictionary_expansion.sql'] == 'feedback'
assert ownership['migrationOwners']['V14__localization_language_registry.sql'] == 'localization'
assert ownership['migrationOwners']['V15__sensitive_word_category_dictionary.sql'] == 'sensitive-words'
assert active_names == {ownership['baseline'], *ownership['migrationOwners']}, active_names ^ {ownership['baseline'], *ownership['migrationOwners']}
assert {'V1__platform_initialization.sql', 'V2__identity_argon2_password_hash.sql',
        'V3__group_platform_navigation.sql', 'V13__business_dictionary_expansion.sql',
        'V14__localization_language_registry.sql',
        'V15__sensitive_word_category_dictionary.sql'} <= active_names
assert 'former V1..V48 scripts' in canonical
assert 'CREATE TABLE public.platform_navigation_menu' in canonical
assert 'CREATE TABLE public.platform_sms_delivery' in canonical
assert 'CREATE FUNCTION public.platform_visible_users' in canonical
assert 'CREATE FUNCTION public.platform_visible_organizations' in canonical

expected_tables = set(ownership['tableOwners'])
actual_tables = set(filter(None, sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'platform_%' ORDER BY tablename").splitlines()))
assert actual_tables == expected_tables, (expected_tables - actual_tables, actual_tables - expected_tables)
expected_builtin_sections = {
    'home', 'files', 'modules', 'monitoring', 'users', 'online-users', 'roles',
    'organizations', 'dictionaries', 'files-all', 'notifications', 'branding',
    'smtp', 'sms', 'profile-fields', 'agreements', 'watermark', 'maintenance',
    'security', 'passkey', 'audit', 'mail-deliveries', 'sms-deliveries',
    'event-outbox', 'feedback', 'navigation', 'sensitive-words', 'localization',
    'ai-capabilities', 'ai-console', 'ai-judgment', 'ai-models',
}
actual_builtin_sections = set(filter(None, sql("SELECT section_key FROM platform_navigation_menu WHERE built_in AND NOT deleted AND menu_type='PAGE' ORDER BY section_key").splitlines()))
assert actual_builtin_sections == expected_builtin_sections, (expected_builtin_sections - actual_builtin_sections, actual_builtin_sections - expected_builtin_sections)
assert int(sql("SELECT count(*) FROM platform_navigation_menu WHERE built_in AND NOT deleted")) == 38
assert int(sql("SELECT count(*) FROM platform_dictionary_item")) >= 18
assert int(sql("SELECT count(*) FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='work_order_feedback_category' AND d.enabled AND i.enabled AND i.value IN ('QUESTION','BUG','FEATURE','ACCOUNT','OTHER')")) == 5
assert int(sql("SELECT count(*) FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category' AND i.value IN ('DEFAULT','IMPORTED')")) == 2
assert int(sql("SELECT count(*) FROM (SELECT DISTINCT w.category AS value FROM platform_sensitive_word w WHERE w.deleted=false EXCEPT SELECT i.value FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category') seeded_categories")) == 0
assert int(sql("SELECT count(*) FROM platform_localization_locale WHERE enabled AND built_in AND locale_code IN ('zh-CN','zh-TW','en','ja','ko','fr','es','de')")) == 8
assert int(sql("SELECT count(*) FROM platform_localization_locale WHERE enabled AND (source_locale NOT IN (SELECT locale_code FROM platform_localization_locale WHERE enabled) OR fallback_locale NOT IN (SELECT locale_code FROM platform_localization_locale WHERE enabled))")) == 0
assert sql("SELECT string_agg(module_id, ',' ORDER BY module_id) FROM platform_module_state") == 'audit-console,feedback,localization,sensitive-words'
assert int(sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname IN ('platform_visible_users','platform_visible_organizations')")) == 2
assert int(sql("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN ('platform_navigation_page_path_uq','platform_navigation_page_section_uq','platform_sms_pending','platform_sms_leases','platform_module_package_active_uq','platform_module_package_lookup','platform_passkey_challenge_expiry','platform_passkey_credential_user','platform_feedback_category_recent','platform_sensitive_word_category_recent')")) == 10
assert int(sql("SELECT count(*) FROM pg_constraint WHERE conname IN ('platform_file_orphan_owner_consistency','platform_event_outbox_check','platform_sms_delivery_check','platform_navigation_shape_ck','platform_event_replay_approval_reason_check','platform_module_package_artifact_ref_check','platform_module_package_status_check','platform_module_package_identity_uq','platform_passkey_challenge_kind_check','platform_passkey_credential_sign_count_check','platform_feedback_category_check')")) == 11
assert 'https' in sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='platform_module_package_artifact_ref_check'")
assert int(sql("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name='platform_user' AND column_name='password_hash'")) == 256
assert int(sql("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name='platform_feedback' AND column_name='category'")) == 100
print('PASS: active migration ownership, PostgreSQL baseline, feedback category contract, platform tables, navigation entries, query indexes and invariant constraints')
