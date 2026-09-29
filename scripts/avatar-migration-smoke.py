from smoke_support import sql
from pathlib import Path
from historical_sql import read as historical_sql
from uuid import uuid4
s=uuid4().hex
users='qa_avatar_users_'+s;files='qa_avatar_files_'+s;media='qa_avatar_media_'+s
a=str(uuid4());b=str(uuid4());image=str(uuid4());raw=str(uuid4())
migration=historical_sql('V22__account_avatars.sql').replace('platform_account_media',media).replace('platform_file',files).replace('platform_user',users)
sql(f"""BEGIN;
CREATE TABLE {users}(id UUID PRIMARY KEY);
CREATE TABLE {files}(id UUID PRIMARY KEY,owner_id UUID REFERENCES {users}(id) ON DELETE SET NULL,state VARCHAR(20));
{migration}
INSERT INTO {users} VALUES('{a}'),('{b}');
INSERT INTO {files}(id,owner_id,state,media_type) VALUES('{image}','{a}','READY','image/png'),('{raw}','{a}','READY','application/octet-stream');
INSERT INTO {media}(user_id) VALUES('{a}'),('{b}');
DO $$ BEGIN
 BEGIN UPDATE {media} SET avatar_file_id='{raw}' WHERE user_id='{a}'; RAISE EXCEPTION 'Raw content must not be inline'; EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN UPDATE {media} SET avatar_file_id='{image}' WHERE user_id='{b}'; RAISE EXCEPTION 'Foreign image must not bind'; EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 UPDATE {media} SET avatar_file_id='{image}' WHERE user_id='{a}';
 BEGIN UPDATE {files} SET state='DELETING' WHERE id='{image}'; RAISE EXCEPTION 'Referenced image must stay READY'; EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN UPDATE {files} SET owner_id='{b}' WHERE id='{image}'; RAISE EXCEPTION 'Referenced owner must stay unchanged'; EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN DELETE FROM {files} WHERE id='{image}'; RAISE EXCEPTION 'Referenced bytes must stay registered'; EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 DELETE FROM {users} WHERE id='{a}';
 ASSERT NOT EXISTS(SELECT 1 FROM {media} WHERE user_id='{a}');
 ASSERT (SELECT owner_id IS NULL FROM {files} WHERE id='{image}');
END $$;
ROLLBACK;""")
print('PASS: normalized type, ownership and READY reference constraints; referenced deletion denied; account deletion cascades binding and orphans files; fixtures rolled back')
