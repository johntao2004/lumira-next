"""Self-service profile writes cannot change authority, identity or concurrent edits."""
from smoke_support import *
import uuid
name='qa_profile_'+uuid.uuid4().hex
try:
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+name+"','Profile QA',password_hash FROM platform_user WHERE username='admin'")
    c=client();assert login(c,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert call(client(),'/account/profile')[0]==401
    status,body=call(c,'/account/profile');assert status==200
    profile=json.loads(body);assert set(profile)=={'id','username','displayName','realName','birthMonth','region','version','schemaVersion','fields'}
    values={'displayName':' Updated QA ','realName':'Example','birthMonth':'2000-02','region':'Test region','version':profile['version'],'username':'admin','enabled':False,'authorities':['platform:users:write']}
    assert call(c,'/account/profile',method='PUT',data=json.dumps(values).encode(),headers={'Content-Type':'application/json'})[0]==403
    status,body=write(c,'/account/profile','PUT',values);assert status==200,(status,body)
    result=json.loads(body);assert result['displayName']=='Updated QA' and result['username']==name and result['version']==1
    assert write(c,'/account/profile','PUT',values)[0]==409
    for invalid in [{'displayName':' '},{'birthMonth':'2000-13'},{'birthMonth':'2999-01'},{'region':'x'*201}]:
        assert write(c,'/account/profile','PUT',{**values,'version':1,**invalid})[0]==400
    status,body=call(c,'/account/profile');assert status==200 and json.loads(body)==result
    status,body=call(c,'/auth/me');assert status==200 and json.loads(body)['displayName']=='Updated QA' and not json.loads(body)['authorities']
    assert sql("SELECT count(*) FROM platform_audit WHERE actor='"+name+"' AND action='profile.update'")=='1'
    print('PASS: self profile, field isolation, CSRF, version conflicts, validation rollback, current identity refresh and audit')
finally:
    sql("DELETE FROM platform_audit WHERE actor='"+name+"'; DELETE FROM spring_session WHERE principal_name='"+name+"'; DELETE FROM platform_user WHERE username='"+name+"'")
