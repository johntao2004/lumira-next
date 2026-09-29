"""SMTP configuration only: isolated local fixtures, no connection or mail sending."""
from smoke_support import *
from uuid import uuid4
from concurrent.futures import ThreadPoolExecutor

suffix=uuid4().hex
names={k:'qa_smtp_'+suffix+'_'+k for k in ['writer','reader','limited']}
ids={k:str(uuid4()) for k in names};role=str(uuid4());created=None;installed=False
trigger='qa_smtp_'+suffix
path='/platform/settings/smtp'
actors={k:client() for k in names}
try:
    for k in names:
        sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{ids[k]}','{names[k]}','SMTP QA',password_hash FROM platform_user WHERE username='admin'")
    for k,grants in [('writer',['read','write']),('reader',['read'])]:
        for grant in grants:sql(f"INSERT INTO platform_authority(user_id,authority) VALUES('{ids[k]}','platform:settings:{grant}')")
    sql(f"INSERT INTO platform_role(id,code,name,data_scope) VALUES('{role}','{suffix}','SMTP scoped QA','SELF'); INSERT INTO platform_role_authority(role_id,authority) VALUES('{role}','platform:settings:write'); INSERT INTO platform_user_role(user_id,role_id) VALUES('{ids['limited']}','{role}')")
    for k in names:assert login(actors[k],names[k],config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    writer=actors['writer']
    def read():
        status,body=call(writer,path);assert status==200,status
        return json.loads(body)
    original=read()
    assert original['version']==0,'SMTP already configured; refusing to overwrite user settings'
    assert original['encryptionConfigured'],'Initialize persistent local secret key and restart first'
    assert original['passwordConfigured'] is False
    data={**original['data'],'enabled':True,'host':'smtp.example.test','username':'smtp-qa','fromAddress':'noreply@example.test','fromName':'SMTP QA'}
    password='fixture-'+uuid4().hex
    def payload(version,action='KEEP',value=None,settings=None):
        credentials={'action':action}
        if value is not None:credentials['value']=value
        return {'version':version,'data':settings or data,'credentials':credentials,'changeReason':'SMTP isolated verification'}
    def save(p,actor=writer):return write(actor,path,'PUT',p)
    p=payload(0,'REPLACE',password)
    assert call(client(),path)[0] in (401,403)
    assert call(actors['reader'],path)[0]==200
    assert save(p,actors['reader'])[0]==403
    assert save(p,actors['limited'])[0]==403
    assert call(writer,path,'PUT',json.dumps(p).encode(),{'Content-Type':'application/json'})[0]==403
    assert save(payload(0))[0]==400
    assert save(payload(0,'REPLACE',''))[0]==400
    assert save(payload(0,'KEEP','******'))[0]==400
    status,body=save(p);assert status==200,status
    created=sql("SELECT id FROM platform_setting WHERE section='smtp'")
    context='settings:'+created+':smtp-password'
    assert password.encode() not in body and read()['passwordConfigured']
    def secret():return sql(f"SELECT version||':'||coalesce(ciphertext,'CLEARED') FROM platform_secret WHERE secret_context='{context}'")
    encrypted=secret();assert encrypted.startswith('1:v1:') and password not in encrypted
    assert save(p)[0]==409
    assert save(payload(1))[0]==200 and secret()==encrypted
    assert save(payload(2,settings={**data,'host':'other.example.test'}))[0]==400
    assert read()['version']==2
    assert save(payload(2,'CLEAR'))[0]==400
    for version in [1,2]:
        status,body=call(writer,path+'/history/'+str(version));assert status==200
        assert password.encode() not in body and b'ciphertext' not in body and b'credentials' not in body
    # A late audit failure must roll back BOTH settings/history and secret replacement.
    sql(f"""CREATE FUNCTION {trigger}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF NEW.actor='{names['writer']}' AND NEW.action='setting.smtpPasswordUpdated' THEN RAISE EXCEPTION 'SMTP audit fixture rejection'; END IF;
    RETURN NEW; END $$; CREATE TRIGGER {trigger} BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION {trigger}();""")
    installed=True
    assert save(payload(2,'REPLACE','new-fixture-password'))[0]==500
    assert read()['version']==2 and secret()==encrypted
    assert sql(f"SELECT count(*) FROM platform_setting_revision WHERE setting_id='{created}'")=='2'
    sql(f'DROP TRIGGER {trigger} ON platform_audit');installed=False
    # Two sessions submit the same version: exactly one succeeds.
    second=client();assert login(second,names['writer'],config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda c:save(payload(2),c)[0],[writer,second]))
    assert sorted(results)==[200,409],results
    assert secret()==encrypted and read()['version']==3
    disabled={**data,'enabled':False}
    assert save(payload(3,'CLEAR',settings=disabled))[0]==200
    assert secret()=='2:CLEARED' and not read()['passwordConfigured']
    rollback={'version':4,'sourceVersion':1,'credentials':{'action':'KEEP'},'changeReason':'Restore SMTP'}
    assert write(writer,path+'/rollback','POST',rollback)[0]==400
    rollback['credentials']={'action':'REPLACE','value':'replacement-fixture'}
    status,body=write(writer,path+'/rollback','POST',rollback);assert status==200,status
    assert json.loads(body)['version']==5 and read()['passwordConfigured']
    assert secret().startswith('3:v1:')
    history=json.loads(call(writer,path+'/history')[1]);assert history['total']==5 and history['items'][0]['sourceVersion']==1
    assert write(writer,path+'/rollback','POST',rollback)[0]==409
    print('PASS: SMTP permissions/CSRF, explicit credential actions, encrypted storage, secret-free history, destination protection, audit rollback, concurrent version conflict, clear and explicit rollback credentials; no mail sent')
finally:
    if installed:sql(f'DROP TRIGGER IF EXISTS {trigger} ON platform_audit')
    sql(f'DROP FUNCTION IF EXISTS {trigger}()')
    if created:
        sql(f"DELETE FROM platform_secret WHERE secret_context='settings:{created}:smtp-password'; DELETE FROM platform_setting_revision WHERE setting_id='{created}'; DELETE FROM platform_audit WHERE target_id='{created}'; DELETE FROM platform_setting WHERE id='{created}'")
    for k in names:
        sql(f"DELETE FROM spring_session WHERE principal_name='{names[k]}'; DELETE FROM platform_audit WHERE actor='{names[k]}'; DELETE FROM platform_user WHERE id='{ids[k]}'")
    sql(f"DELETE FROM platform_role WHERE id='{role}'")
