from smoke_support import *
from uuid import uuid4
owner=str(uuid4());suffix=uuid4().hex;name='qa_file_audit_'+suffix;trigger='qa_file_audit_'+suffix;created=[];installed=False
actor=client()
def fail(action):
    global installed
    sql(f"""CREATE OR REPLACE FUNCTION {trigger}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF NEW.actor='{name}' AND NEW.action='{action}' THEN RAISE EXCEPTION 'Audit fixture rejection'; END IF;
    RETURN NEW; END $$;""")
    if not installed:
        sql(f"CREATE TRIGGER {trigger} BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION {trigger}()")
        installed=True
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{owner}','{name}','Audit QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    def upload():return call(actor,'/account/files?filename=audit.txt','POST',b'audit bytes',{'Content-Type':'application/octet-stream',**token(actor)})
    fail('file.upload');status,_=upload();assert status==500,status
    assert sql(f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}'")=='0'
    assert sql(f"SELECT count(*) FROM platform_audit WHERE actor='{name}'")=='0'
    fail('file.deleteRequested');status,body=upload();assert status==201
    entry=json.loads(body);id=entry['id'];created.append(id);path='/account/files/'+id
    assert call(actor,path+'?version=1','DELETE',headers=token(actor))[0]==500
    assert json.loads(call(actor,path)[1])['state']=='READY'
    assert call(actor,path+'/content')[1]==b'audit bytes'
    assert sql(f"SELECT count(*) FROM platform_audit WHERE target_id='{id}'")=='1'
    fail('file.delete');assert call(actor,path+'?version=1','DELETE',headers=token(actor))[0]==500
    pending=json.loads(call(actor,path)[1]);assert pending['state']=='DELETING' and pending['version']==2
    assert sql(f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{id}'")=='file.deleteRequested,file.upload'
    fail('none');assert call(actor,path+'?version=2','DELETE',headers=token(actor))[0]==204
    created.remove(id)
    assert sql(f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{id}'")=='file.delete,file.deleteRequested,file.upload'
    print('PASS: upload audit failure rolls back publication metadata; delete-request failure preserves bytes; completion audit failure retains retry state; retry records each event once')
finally:
    if installed:sql(f'DROP TRIGGER IF EXISTS {trigger} ON platform_audit')
    sql(f'DROP FUNCTION IF EXISTS {trigger}()')
    for id in created:
        status,body=call(actor,'/account/files/'+id)
        if status==200:call(actor,'/account/files/'+id+'?version='+str(json.loads(body)['version']),'DELETE',headers=token(actor))
    sql(f"DELETE FROM platform_audit WHERE actor='{name}'; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{owner}'")
