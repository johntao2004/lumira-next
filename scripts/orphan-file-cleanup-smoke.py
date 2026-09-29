from smoke_support import *
from uuid import uuid4
from pathlib import Path
import time
owner=str(uuid4());name='qa_orphan_'+uuid4().hex;actor=client();ids=[];root=Path(config.get('LUMIRA_FILES_LOCAL_ROOT','.runtime/files'))
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{owner}','{name}','Orphan QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    for filename in ['expired.txt','retained.txt']:
        status,body=call(actor,'/account/files?filename='+filename,'POST',b'retention fixture',{'Content-Type':'application/octet-stream',**token(actor)});assert status==201;ids.append(json.loads(body)['id'])
    expired,retained=ids
    sql(f"UPDATE platform_file SET created_at=now()-interval '30 days' WHERE owner_id='{owner}'; DELETE FROM platform_user WHERE id='{owner}'")
    assert sql(f"SELECT count(*) FROM platform_file WHERE id IN ('{expired}','{retained}') AND orphaned_at>now()-interval '1 minute' AND state='READY'")=='2'
    sql(f"UPDATE platform_file SET orphaned_at=now()-interval '8 days' WHERE id='{expired}'")
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        if sql(f"SELECT count(*) FROM platform_file WHERE id='{expired}'")=='0':break
        time.sleep(2)
    else:raise AssertionError('Orphan cleanup deadline exceeded')
    assert not (root/expired[:2]/(expired+'.blob')).exists()
    assert (root/retained[:2]/(retained+'.blob')).read_bytes()==b'retention fixture'
    assert sql(f"SELECT state FROM platform_file WHERE id='{retained}'")=='READY'
    assert sql(f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{expired}'")=='file.cleanup,file.orphanExpired,file.upload'
    print('PASS: actual account deletion starts retention for old files; scheduled expiry removes only expired orphan bytes/metadata with audit; recent orphan retained')
finally:
    for id in ids:
        for ext in ['.blob','.upload']:(root/id[:2]/(id+ext)).unlink(missing_ok=True)
        sql(f"DELETE FROM platform_audit WHERE target_id='{id}'; DELETE FROM platform_file WHERE id='{id}'")
    sql(f"DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{owner}'")
