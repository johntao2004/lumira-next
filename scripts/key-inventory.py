#!/usr/bin/env python3
"""只统计固定字段的密钥引用，不批准退钥。 Count fixed-field references; never approve retirement."""
import json
import os
import re
import subprocess
import sys
import tempfile

ENCRYPTED = (
    ('platform_secret', 'ciphertext'),
    ('platform_mail_delivery', 'payload'),
    ('platform_sms_delivery', 'payload'),
    ('platform_public_auth_challenge', 'contact_ciphertext'),
    ('plugin_mfa_authenticator', 'secret_envelope'),
    ('plugin_mfa_replacement', 'secret_envelope'),
)
HMAC = (
    ('plugin_mfa_recovery', 'digest'),
    ('platform_account_phone', 'token_hash'),
    ('platform_mfa_recovery_ticket', 'token_digest'),
    ('platform_public_auth_challenge', 'session_digest'),
    ('platform_public_auth_challenge', 'contact_digest'),
    ('platform_public_auth_challenge', 'code_digest'),
    ('platform_registration_completion', 'session_digest'),
    ('platform_registration_completion', 'request_fingerprint'),
    ('platform_public_auth_rate', 'rate_digest'),
    ('platform_captcha_challenge', 'session_digest'),
    ('platform_captcha_proof', 'session_digest'),
    ('platform_rate_bucket', 'bucket_key'),
)


def query(table, column, encrypted):
    # 标识符仅来自源码常量，不能接受任意 SQL。 Identifiers come only from source constants.
    header = f"'table','{table}','column','{column}'"
    if encrypted:
        return f"""SELECT json_build_object({header},'kind','encrypted','key_id',key_id,'count',n)::text
FROM (SELECT CASE WHEN {column} ~ '^v1:[A-Za-z0-9_-]{{1,32}}:[A-Za-z0-9_-]+$'
THEN split_part({column},':',2) ELSE NULL END AS key_id, count(*) AS n
FROM public.{table} WHERE {column} IS NOT NULL GROUP BY 1 LIMIT 1001) AS counts
UNION ALL SELECT json_build_object({header},'kind','encrypted','key_id',NULL,'count',0)::text
WHERE NOT EXISTS (SELECT 1 FROM public.{table} WHERE {column} IS NOT NULL);"""
    return f"SELECT json_build_object({header},'kind','unattributable_hmac_or_derived','count',count({column}))::text FROM public.{table};"


def build_sql():
    parts = ["\\set ON_ERROR_STOP on", "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;",
             "SET LOCAL statement_timeout = '15s';", "SET LOCAL lock_timeout = '3s';",
             "SELECT EXISTS(SELECT 1 FROM public.platform_plugin_installation WHERE module_id='mfa' AND installed) AS mfa_installed \\gset"]
    for encrypted, entries in ((True, ENCRYPTED), (False, HMAC)):
        for table, column in entries:
            if table.startswith('plugin_'):
                parts.extend([f"SELECT to_regclass('public.{table}') IS NOT NULL AS present \\gset", '\\if :present', query(table, column, encrypted), '\\else',
                              f"SELECT json_build_object('table','{table}','column','{column}','kind',CASE WHEN :'mfa_installed'::boolean THEN 'missing_installed_optional' ELSE 'absent_optional' END)::text;", '\\endif'])
            else:
                parts.append(query(table, column, encrypted))
    parts.append('COMMIT;')
    return '\n'.join(parts) + '\n'


def main():
    # libpq 环境提供连接信息；不接受 DSN 参数，也不输出底层错误。 Use libpq env; never print DSNs/errors.
    env = os.environ.copy()
    env['PGCONNECT_TIMEOUT'] = '5'
    result = {'status': 'incomplete', 'active_key_change_decision': 'blocked', 'retirement_decision': 'blocked', 'scope': 'fixed_public_allowlist_only',
              'limitations': ['backup_key_dependencies_not_verified', 'third_party_plugin_storage_not_verified',
                              'hmac_key_attribution_unavailable', 'active_only_hmac_challenges_and_idempotency_require_migration', 'envelope_syntax_is_not_decryption_validation'],
              'references': []}
    try:
        # 有界执行，先成功提交再发布聚合结果。 Bound execution; publish aggregates only after successful commit.
        with tempfile.TemporaryFile() as output:
            completed = subprocess.run(['psql', '-X', '-q', '-A', '-t', '-w', '-v', 'ON_ERROR_STOP=1'],
                                       input=build_sql().encode(), stdout=output, stderr=subprocess.DEVNULL,
                                       env=env, timeout=60, check=False)
            if completed.returncode != 0:
                raise ValueError('query_failed')
            output.seek(0)
            raw = output.read(1048577)
            if len(raw) > 1048576:
                raise ValueError('output_limit')
        rows = [json.loads(line) for line in raw.decode().splitlines() if line.strip()]
        if len(rows) > 1000:
            raise ValueError('aggregate_limit')
        allowed = set(ENCRYPTED + HMAC)
        observed = set()
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError('unexpected_result')
            if not isinstance(row.get('table'), str) or not isinstance(row.get('column'), str):
                raise ValueError('unexpected_identifier')
            pair = (row.get('table'), row.get('column'))
            if pair not in allowed:
                raise ValueError('unexpected_result')
            kind = row.get('kind')
            keys = {'table', 'column', 'kind'}
            if kind in ('absent_optional', 'missing_installed_optional'):
                if not pair[0].startswith('plugin_'):
                    raise ValueError('unexpected_optional')
            else:
                keys.add('count')
                if type(row.get('count')) is not int or row['count'] < 0:
                    raise ValueError('unexpected_count')
                if pair in ENCRYPTED:
                    keys.add('key_id')
                    if kind != 'encrypted':
                        raise ValueError('unexpected_envelope')
                    key = row.get('key_id')
                    if key is not None and (not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', key)):
                        raise ValueError('unexpected_key_id')
                elif kind != 'unattributable_hmac_or_derived':
                    raise ValueError('unexpected_hmac')
            if set(row) != keys:
                raise ValueError('unexpected_fields')
            observed.add(pair)
        # 空输出不能冒充空库存；每个字段必须有观测。 Empty output never proves an empty inventory.
        if observed != allowed:
            raise ValueError('incomplete_coverage')
        result['references'] = rows
        invalid = any((r.get('kind') == 'encrypted' and r.get('key_id') is None and r.get('count', 0) > 0) or r.get('kind') == 'missing_installed_optional' for r in rows)
        result['status'] = 'incomplete' if invalid else 'observed'
        if invalid:
            result['error'] = 'invalid_envelope_or_missing_installed_table'
        print(json.dumps(result, ensure_ascii=False))
        return 1 if invalid else 0
    except (OSError, ValueError, subprocess.TimeoutExpired):
        result['error'] = 'inventory_unavailable_or_invalid'
        print(json.dumps(result, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    if len(sys.argv) != 1:
        print('{"status":"incomplete","active_key_change_decision":"blocked","retirement_decision":"blocked","error":"arguments_not_supported"}')
        raise SystemExit(2)
    raise SystemExit(main())
