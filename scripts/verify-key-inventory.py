#!/usr/bin/env python3
"""仅在显式隔离 QA 中核验只读库存和失败关闭。 Verify inventory in explicitly disposable QA only."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('inventory', ROOT / 'scripts/key-inventory.py')
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


def main():
    if os.environ.get('LUMIRA_DISPOSABLE_QA') != 'true' or os.environ.get('PGHOST') not in ('127.0.0.1', 'localhost'):
        raise RuntimeError('Explicit disposable QA opt-in required')
    database = 'lumira_inventory_' + uuid.uuid4().hex
    reader = 'inventory_reader_' + uuid.uuid4().hex
    env = dict(os.environ, PGDATABASE=database)
    checks = []

    def sql(statement, target=database):
        return subprocess.run(['psql', '-X', '-q', '-A', '-t', '-w', '-v', 'ON_ERROR_STOP=1', '-d', target],
                              input=statement, text=True, capture_output=True, timeout=60, check=True)

    def observe(name, expected=0, reader_mode=False):
        selected = dict(env)
        if reader_mode:
            selected['PGOPTIONS'] = '-c role=' + reader
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/key-inventory.py')],
                                env=selected, text=True, capture_output=True, timeout=70)
        report = json.loads(result.stdout)
        if result.returncode != expected or report['active_key_change_decision'] != 'blocked' or report['retirement_decision'] != 'blocked':
            raise RuntimeError('Inventory contract failed: ' + name)
        checks.append(name)
        return report

    # 随机库和角色仅属于本次 QA；不连接或更改生产。 Random resources belong only to this QA run.
    try:
        sql('CREATE DATABASE ' + database, 'postgres')
        sql((ROOT / 'backend/src/main/resources/db/migration/B52__platform_initialization.sql').read_text())
        observe('real B52 without optional MFA')
        sql((ROOT / 'plugins/mfa/migrations/V7__plugin_initialization.sql').read_text())
        sql(f'CREATE ROLE {reader} NOLOGIN; GRANT CONNECT ON DATABASE {database} TO {reader}; '
            f'GRANT USAGE ON SCHEMA public TO {reader}; GRANT SELECT ON ALL TABLES IN SCHEMA public TO {reader};')
        report = observe('real B52 plus MFA under read-only role', reader_mode=True)
        if len(report['references']) != 18:
            raise RuntimeError('Empty schema coverage mismatch')
        # 简化表只承载畸形输入测试；真实初始化兼容性已单独执行。 Simplified tables isolate malformed fixtures.
        sql('DROP SCHEMA public CASCADE; CREATE SCHEMA public;')
        tables = {}
        for table, column in inventory.ENCRYPTED + inventory.HMAC:
            tables.setdefault(table, set()).add(column)
        sql('CREATE TABLE public.platform_plugin_installation(module_id text, installed boolean);' +
            ''.join(f"CREATE TABLE public.{table} ({','.join(column + ' text' for column in sorted(columns))});" for table, columns in tables.items()))
        for table, column in inventory.ENCRYPTED:
            sql(f"INSERT INTO public.{table}({column}) VALUES ('v1:oldkey:QUJD'),('v1:newkey:REVG');")
        for table, column in inventory.HMAC:
            sql(f"INSERT INTO public.{table}({column}) VALUES ('synthetic_digest');")
        report = observe('old and new envelopes plus unattributable digests')
        if not any(r.get('key_id') == 'oldkey' and r['count'] == 1 for r in report['references']):
            raise RuntimeError('Missing old-key count')
        sql("UPDATE public.platform_secret SET ciphertext='malformed';")
        observe('malformed envelope fails closed', 1)
        sql('TRUNCATE public.platform_secret; DROP TABLE public.plugin_mfa_recovery;')
        observe('retained optional storage still counted')
        sql("INSERT INTO public.platform_plugin_installation VALUES ('mfa',true);")
        observe('installed MFA missing table fails closed', 1)
        sql('DELETE FROM public.platform_plugin_installation; ALTER TABLE public.platform_sms_delivery DROP COLUMN payload;')
        observe('missing required column fails closed', 1)
        sql('ALTER TABLE public.platform_sms_delivery ADD COLUMN payload text;')
        sql(f'GRANT USAGE ON SCHEMA public TO {reader}; GRANT SELECT ON ALL TABLES IN SCHEMA public TO {reader}; '
            f'REVOKE SELECT ON public.platform_secret FROM {reader};')
        observe('insufficient reader permissions fail closed', 1, reader_mode=True)
    finally:
        # 即使前一步失败仍尝试两项清理。 Always attempt both cleanup actions after failure.
        try:
            sql('DROP DATABASE IF EXISTS ' + database + ' WITH (FORCE)', 'postgres')
        finally:
            sql('DROP ROLE IF EXISTS ' + reader, 'postgres')
    print(json.dumps({'status': 'passed', 'checks': checks}))


if __name__ == '__main__':
    main()
