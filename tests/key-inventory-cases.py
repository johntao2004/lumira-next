#!/usr/bin/env python3
"""合成子进程返回测试，不连接数据库。 Synthetic subprocess tests; no database connection."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
spec = importlib.util.spec_from_file_location('inventory', Path(__file__).resolve().parents[1] / 'scripts/key-inventory.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class InventoryTest(unittest.TestCase):
    def complete_rows(self):
        return ([{'table': t, 'column': c, 'kind': 'encrypted', 'key_id': None, 'count': 0}
                 for t, c in m.ENCRYPTED] +
                [{'table': t, 'column': c, 'kind': 'unattributable_hmac_or_derived', 'count': 0}
                 for t, c in m.HMAC])

    def execute(self, rows=(), code=0, timeout=False):
        def fake_run(args, **kwargs):
            self.assertEqual(args[:5], ['psql', '-X', '-q', '-A', '-t'])
            self.assertIn('-w', args)
            self.assertEqual(kwargs['stderr'], subprocess.DEVNULL)
            self.assertEqual(kwargs['timeout'], 60)
            sql = kwargs['input'].decode()
            self.assertIn('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;', sql)
            self.assertTrue(sql.endswith('COMMIT;\n'))
            if timeout:
                raise subprocess.TimeoutExpired('psql', 60)
            kwargs['stdout'].write(('\n'.join(json.dumps(x) for x in rows)).encode())
            return subprocess.CompletedProcess(args, code)
        captured = io.StringIO()
        with patch.object(m.subprocess, 'run', fake_run), contextlib.redirect_stdout(captured):
            status = m.main()
        return status, json.loads(captured.getvalue())

    def test_success_never_approves_retirement(self):
        rows = self.complete_rows()
        rows[0].update(key_id='oldkey', count=2)
        status, result = self.execute(rows)
        self.assertEqual(status, 0)
        self.assertEqual(result['retirement_decision'], 'blocked')
        self.assertEqual(result['active_key_change_decision'], 'blocked')
        self.assertNotIn('safe_to_retire', result)

    def test_failed_query_discards_partial_output(self):
        status, result = self.execute([{'private':'must_not_escape'}], code=1)
        self.assertEqual(status, 1)
        self.assertEqual(result['references'], [])

    def test_timeout_is_fixed_error(self):
        status, result = self.execute(timeout=True)
        self.assertEqual(status, 1)
        self.assertEqual(result['error'], 'inventory_unavailable_or_invalid')

    def test_invalid_envelope_blocks_completion(self):
        rows = self.complete_rows()
        rows[0].update(key_id=None, count=1)
        status, result = self.execute(rows)
        self.assertEqual(status, 1)

    def test_installed_optional_missing_blocks_completion(self):
        rows = self.complete_rows()
        rows[6] = {'table':'plugin_mfa_recovery','column':'digest','kind':'missing_installed_optional'}
        status, result = self.execute(rows)
        self.assertEqual(status, 1)

    def test_key_ids_do_not_collide_with_empty_or_invalid_markers(self):
        rows = self.complete_rows()
        rows[0].update(key_id='__invalid__', count=2)
        rows[1].update(key_id='__none__', count=1)
        self.assertEqual(self.execute(rows)[0], 0)

    def test_malformed_identifiers_and_missing_key_return_fixed_error(self):
        for change in ('identifier', 'missing-key'):
            rows = self.complete_rows()
            if change == 'identifier':
                rows[0]['table'] = ['private']
            else:
                del rows[0]['key_id']
            status, result = self.execute(rows)
            self.assertEqual(status, 1)
            self.assertEqual(result['references'], [])
            self.assertEqual(result['error'], 'inventory_unavailable_or_invalid')

    def test_empty_output_is_incomplete(self):
        status, result = self.execute([])
        self.assertEqual(status, 1)
        self.assertEqual(result['references'], [])

    def test_unexpected_fields_are_never_published(self):
        rows = self.complete_rows()
        rows[0]['secret'] = 'must_not_escape'
        status, result = self.execute(rows)
        self.assertEqual(status, 1)
        self.assertNotIn('must_not_escape', json.dumps(result))

    def test_empty_fields_are_observed_without_approving_rotation(self):
        status, result = self.execute(self.complete_rows())
        self.assertEqual(status, 0)
        self.assertEqual(len(result['references']), 18)
        self.assertEqual(result['active_key_change_decision'], 'blocked')

    def test_invalid_count_is_rejected(self):
        rows = self.complete_rows()
        rows[0]['count'] = True
        self.assertEqual(self.execute(rows)[0], 1)

    def test_sql_has_no_writes_or_arbitrary_identifiers(self):
        sql = m.build_sql()
        for operation in ('DELETE ', 'UPDATE ', 'INSERT ', 'CREATE ', 'DROP '):
            self.assertNotIn(operation, sql)
        for table, column in m.ENCRYPTED + m.HMAC:
            self.assertIn('public.' + table, sql)

if __name__ == '__main__':
    unittest.main()
