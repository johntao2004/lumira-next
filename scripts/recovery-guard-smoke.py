"""Exercise the installed recovery functions in a transaction that is always rolled back.
No temporary permission/account changes become visible to application connections.
"""
from smoke_support import *
import uuid
one=str(uuid.uuid4());two=str(uuid.uuid4());role=str(uuid.uuid4())
sql(f"""
BEGIN;
SELECT pg_advisory_xact_lock(481026);
UPDATE platform_user SET enabled=false;
INSERT INTO platform_user(id,username,display_name,password_hash) VALUES
 ('{one}','qa_recovery_{one}','Recovery one','unused'),('{two}','qa_recovery_{two}','Recovery two','unused');
INSERT INTO platform_authority(user_id,authority) SELECT '{one}',authority FROM
 (VALUES ('platform:users:write'),('platform:roles:write'),('platform:settings:write'),('platform:settings:read')) p(authority);
DO $$ BEGIN ASSERT platform_has_recovery_administrators(); ASSERT platform_is_last_recovery_administrator('{one}'); END $$;
INSERT INTO platform_authority(user_id,authority) SELECT '{two}',authority FROM
 (VALUES ('platform:users:write'),('platform:roles:write'),('platform:settings:write')) p(authority);
DO $$ BEGIN ASSERT platform_is_last_recovery_administrator('{one}'); END $$;
INSERT INTO platform_authority VALUES('{two}','platform:settings:read');
DO $$ BEGIN ASSERT platform_has_recovery_administrators(); ASSERT NOT platform_is_last_recovery_administrator('{one}'); ASSERT NOT platform_is_last_recovery_administrator('{two}'); END $$;
DELETE FROM platform_authority WHERE user_id='{two}' AND authority='platform:settings:read';
DELETE FROM platform_authority WHERE user_id='{one}' AND authority='platform:settings:read';
DO $$ BEGIN ASSERT NOT platform_has_recovery_administrators(); END $$;
INSERT INTO platform_authority VALUES('{two}','platform:settings:read');
DO $$ BEGIN ASSERT platform_has_recovery_administrators(); ASSERT platform_is_last_recovery_administrator('{two}'); END $$;
UPDATE platform_user SET enabled=false WHERE id='{two}';
DO $$ BEGIN ASSERT NOT platform_has_recovery_administrators(); END $$;
UPDATE platform_user SET enabled=true WHERE id='{two}';
DELETE FROM platform_authority WHERE user_id='{two}' AND authority LIKE 'platform:settings:%';
INSERT INTO platform_role(id,code,name,data_scope) VALUES('{role}','qa_recovery_{role}','Recovery role','ALL');
INSERT INTO platform_role_authority(role_id,authority) VALUES('{role}','platform:settings:read'),('{role}','platform:settings:write');
INSERT INTO platform_user_role(user_id,role_id) VALUES('{two}','{role}');
DO $$ BEGIN ASSERT platform_has_recovery_administrators(); END $$;
UPDATE platform_role SET data_scope='SELF' WHERE id='{role}';
DO $$ BEGIN ASSERT NOT platform_has_recovery_administrators(); END $$;
UPDATE platform_role SET data_scope='ALL',enabled=false WHERE id='{role}';
DO $$ BEGIN ASSERT NOT platform_has_recovery_administrators(); END $$;
ROLLBACK;
""")
print('PASS: last configuration reader/writer, disabled account, global role scope and disabled role recovery guards; all fixture changes rolled back')
