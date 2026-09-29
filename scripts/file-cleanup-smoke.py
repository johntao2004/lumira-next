from smoke_support import *
from uuid import uuid4
from pathlib import Path
import time
owner=str(uuid4());name='qa_cleanup_'+uuid4().hex;pending=str(uuid4());ready=str(uuid4());bad=str(uuid4());expired=str(uuid4());uploaded=None;obstacle=None;temporary=None
actor=client()
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{owner}','{name}','Cleanup QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    status,body=call(actor,'/account/files?filename=cleanup.txt','POST',b'cleanup me',{'Content-Type':'application/octet-stream',**token(actor)});assert status==201
    uploaded=json.loads(body)['id']
    root=Path(config.get('LUMIRA_FILES_LOCAL_ROOT','.runtime/files'))
    real=root/uploaded[:2]/(uploaded+'.blob');assert real.is_file()
    obstacle=root/bad[:2]/(bad+'.blob');obstacle.mkdir(parents=True)
    sql(f"""UPDATE platform_file SET state='DELETING',version=version+1,updated_at=now()-interval '10 minutes' WHERE id='{uploaded}';
    INSERT INTO platform_file(id,owner_id,storage_backend,original_name,state,updated_at) VALUES
    ('{pending}','{owner}','local','Active upload','UPLOADING',now()-interval '10 minutes'),
    ('{bad}','{owner}','local','Retry obstacle','DELETING',now()-interval '10 minutes');
    INSERT INTO platform_file(id,owner_id,storage_backend,original_name,state,size_bytes,sha256,updated_at) VALUES
    ('{ready}','{owner}','local','Ready fixture','READY',0,repeat('a',64),now()-interval '10 minutes');""")
    temporary=root/expired[:2]/(expired+'.upload');temporary.parent.mkdir(parents=True,exist_ok=True);temporary.write_bytes(b'abandoned')
    sql(f"INSERT INTO platform_file(id,owner_id,storage_backend,original_name,upload_expires_at) VALUES('{expired}','{owner}','local','Expired fixture',now()-interval '2 minutes')")
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        cleaned=sql(f"SELECT count(*) FROM platform_file WHERE id='{uploaded}'")=='0'
        deferred=sql(f"SELECT updated_at>now()-interval '2 minutes' FROM platform_file WHERE id='{bad}'")=='t'
        recovered=sql(f"SELECT count(*) FROM platform_file WHERE id='{expired}'")=='0'
        if cleaned and deferred and recovered:break
        time.sleep(2)
    else:raise AssertionError('Scheduled cleanup did not complete within deadline')
    assert not real.exists() and not temporary.exists()
    assert sql(f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{expired}'")=='file.cleanup,file.uploadExpired'
    assert sql(f"SELECT count(*) FROM platform_file WHERE id IN ('{pending}','{ready}','{bad}')")=='3'
    assert sql(f"SELECT count(*) FROM platform_audit WHERE target_id='{uploaded}' AND action='file.cleanup'")=='1'
    print('PASS: real scheduled cleanup deletes bytes then metadata/audit; active upload and READY untouched; failed deletion deferred and retained; expired upload staged bytes and reservation reclaimed')
finally:
    if temporary and temporary.exists():temporary.unlink()
    if obstacle and obstacle.exists():obstacle.rmdir()
    if uploaded:
        status,body=call(actor,'/account/files/'+uploaded)
        if status==200:call(actor,'/account/files/'+uploaded+'?version='+str(json.loads(body)['version']),'DELETE',headers=token(actor))
    sql(f"DELETE FROM platform_file WHERE owner_id='{owner}'; DELETE FROM platform_audit WHERE actor='{name}' OR target_id IN ('{uploaded or owner}','{bad}','{expired}'); DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{owner}'")
