from smoke_support import *
import uuid
from pathlib import Path
from historical_sql import read as historical_sql
user=str(uuid.uuid4());dictionary=str(uuid.uuid4());item=str(uuid.uuid4());setting=str(uuid.uuid4());suffix=uuid.uuid4().hex;section='qa_reference_'+suffix
migration=(historical_sql('V16__profile_dictionary_selections.sql').replace('platform_profile_dictionary_selection','qa_selection_'+suffix).replace("'profile-fields'", "'"+section+"'"))
current=json.dumps({'fields':[{'key':'current','dictionaryCode':section}]})
historical=json.dumps({'fields':[{'key':'retired','dictionaryCode':section}]})
sql(f"""BEGIN;
SELECT pg_advisory_xact_lock(481026);
INSERT INTO platform_dictionary(id,code,name,structure) VALUES('{dictionary}','{section}','Migration QA','FLAT');
INSERT INTO platform_dictionary_item(id,dictionary_id,value,label) VALUES('{item}','{dictionary}','option','Option');
INSERT INTO platform_user(id,username,display_name,password_hash,profile_values) VALUES('{user}','{section}','Migration QA','unused',jsonb_build_object('current','{item}','retired','{item}','unrelated','{item}'));
INSERT INTO platform_setting(id,section,version,payload,updated_by) VALUES('{setting}','{section}',2,'{current}','QA');
INSERT INTO platform_setting_revision(setting_id,version,payload,changed_by,change_reason) VALUES('{setting}',1,'{historical}','QA','Fixture');
{migration}
DO $$ BEGIN
 ASSERT (SELECT count(*) FROM qa_selection_{suffix} WHERE user_id='{user}')=2;
 BEGIN
  DELETE FROM platform_dictionary_item WHERE id='{item}';
  RAISE EXCEPTION 'Expected foreign key rejection';
 EXCEPTION WHEN foreign_key_violation OR restrict_violation THEN NULL;
 END;
 DELETE FROM platform_user WHERE id='{user}';
 ASSERT (SELECT count(*) FROM qa_selection_{suffix} WHERE user_id='{user}')=0;
 DELETE FROM platform_dictionary_item WHERE id='{item}';
END $$;
ROLLBACK;
""")
print('PASS: V16 backfill from current and historical schema, unrelated text excluded, FK deletion guard and account cascade; fixture DDL/data rolled back')
