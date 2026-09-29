from smoke_support import sql
from pathlib import Path
from historical_sql import read as historical_sql
from uuid import uuid4
suffix=uuid4().hex
owner=str(uuid4());file=str(uuid4());table='qa_file_'+suffix
migration=historical_sql('V17__file_metadata.sql').replace('platform_file',table)
sql(f"""BEGIN;
INSERT INTO platform_user(id,username,display_name,password_hash) VALUES('{owner}','qa_file_{suffix}','File QA','unused');
{migration}
INSERT INTO {table}(id,owner_id,storage_backend,original_name) VALUES('{file}','{owner}','local','report.pdf');
DO $$ BEGIN
 ASSERT (SELECT state='UPLOADING' AND size_bytes IS NULL AND sha256 IS NULL FROM {table} WHERE id='{file}');
 BEGIN
  UPDATE {table} SET state='READY' WHERE id='{file}';
  RAISE EXCEPTION 'Expected missing content metadata rejection';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 BEGIN
  UPDATE {table} SET size_bytes=-1,sha256=repeat('a',64) WHERE id='{file}';
  RAISE EXCEPTION 'Expected invalid size rejection';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 BEGIN
  UPDATE {table} SET size_bytes=0,sha256='invalid' WHERE id='{file}';
  RAISE EXCEPTION 'Expected invalid digest rejection';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 UPDATE {table} SET state='READY',size_bytes=0,sha256=repeat('a',64),version=1 WHERE id='{file}';
 DELETE FROM platform_user WHERE id='{owner}';
 ASSERT (SELECT owner_id IS NULL AND state='READY' FROM {table} WHERE id='{file}');
END $$;
ROLLBACK;
""")
print('PASS: V17 upload defaults, ready metadata constraints, size/digest validation and retained orphan metadata after account deletion; all fixture DDL/data rolled back')
