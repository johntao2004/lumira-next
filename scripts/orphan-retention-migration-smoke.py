from smoke_support import sql
from pathlib import Path
from historical_sql import read as historical_sql
from uuid import uuid4
suffix=uuid4().hex;table='qa_file_'+suffix;owner=str(uuid4());file=str(uuid4());old=str(uuid4())
migration=historical_sql('V21__orphan_file_retention.sql').replace('platform_file',table)
sql(f"""BEGIN;
INSERT INTO platform_user(id,username,display_name,password_hash) VALUES('{owner}','qa_retention_{suffix}','Retention QA','unused');
CREATE TABLE {table}(id UUID PRIMARY KEY,owner_id UUID REFERENCES platform_user(id) ON DELETE SET NULL,state TEXT NOT NULL DEFAULT 'READY',created_at TIMESTAMPTZ DEFAULT now());
INSERT INTO {table}(id,owner_id,created_at) VALUES('{file}','{owner}',now()-interval '30 days'),('{old}',NULL,now()-interval '30 days');
{migration}
DO $$ BEGIN
 ASSERT (SELECT orphaned_at>=transaction_timestamp() FROM {table} WHERE id='{old}');
 DELETE FROM platform_user WHERE id='{owner}';
 ASSERT (SELECT owner_id IS NULL AND orphaned_at>=transaction_timestamp() FROM {table} WHERE id='{file}');
 INSERT INTO platform_user(id,username,display_name,password_hash) VALUES('{owner}','qa_retention_{suffix}','Retention QA','unused');
 UPDATE {table} SET owner_id='{owner}' WHERE id='{file}';
 ASSERT (SELECT orphaned_at IS NULL FROM {table} WHERE id='{file}');
 BEGIN
  UPDATE {table} SET orphaned_at=now() WHERE id='{file}';
  RAISE EXCEPTION 'Expected owner/orphan constraint';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $$;
ROLLBACK;
""")
print('PASS: old orphan backfill gets full retention, FK owner removal timestamps loss rather than creation, owner reassignment clears marker and consistency enforced; fixtures rolled back')
