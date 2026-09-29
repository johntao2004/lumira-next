"""Role grants are enforced immediately on existing sessions; fixtures always removed."""
from smoke_support import *
import uuid,secrets
prefix='qa_role_'+uuid.uuid4().hex
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
user=None;role=None
try:
    password=secrets.token_urlsafe(24)
    status,body=write(admin,'/platform/users','POST',{'username':prefix,'displayName':'Role QA','password':password});assert status==201
    user=json.loads(body)
    limited=client();assert login(limited,prefix,password)==200
    assert call(limited,'/platform/modules')[0]==403
    data={'code':prefix,'name':'Reader','enabled':True,'dataScope':'ALL','authorities':['platform:modules:read']}
    status,body=write(admin,'/platform/roles','POST',data);assert status==201,(status,body)
    role=json.loads(body)
    assert write(limited,'/platform/roles','POST',data)[0]==403
    assert write(admin,'/platform/roles','POST',data)[0]==409
    assert write(admin,'/platform/users/'+user['id']+'/roles','PUT',{'version':0,'roleIds':[role['id']]})[0]==200
    assert call(limited,'/platform/modules')[0]==200,'Role grant missing on existing session'
    assert write(admin,'/platform/roles/'+role['id']+'?version=0','DELETE',{})[0]==409
    assert write(admin,'/platform/users/'+user['id']+'/roles','PUT',{'version':0,'roleIds':[]})[0]==409
    data.update(version=role['version'],enabled=False)
    status,body=write(admin,'/platform/roles/'+role['id'],'PUT',data);assert status==200,(status,body)
    assert call(limited,'/platform/modules')[0]==403,'Disabled role retained access'
    assert write(admin,'/platform/roles/'+role['id'],'PUT',data)[0]==409
    data.update(version=json.loads(body)['version'],enabled=True,authorities=['platform:roles:write','platform:roles:read'])
    status,body=write(admin,'/platform/roles/'+role['id'],'PUT',data);assert status==200
    assert call(limited,'/platform/roles')[0]==200
    assert write(limited,'/platform/roles','POST',{'code':prefix+'x','name':'Escalation','enabled':True,'authorities':['platform:users:write']})[0]==403
    assert write(admin,'/platform/roles','POST',{'code':prefix+'bad','name':'Bad','enabled':True,'authorities':['unknown']})[0]==400
    assert write(admin,'/platform/users/'+user['id']+'/roles','PUT',{'version':1,'roleIds':[]})[0]==200
    assert call(limited,'/platform/roles')[0]==403,'Removed role retained access'
    assert write(limited,'/platform/roles/'+role['id']+'?version=2','DELETE',{})[0]==403
    assert write(admin,'/platform/roles/'+role['id']+'?version=0','DELETE',{})[0]==409
    assert write(admin,'/platform/roles/'+role['id']+'?version=2','DELETE',{})[0]==204
    assert call(admin,'/platform/roles/'+role['id'])[0]==404
    print('PASS: deletion/in-use/stale/denied checks, role creation, duplicate/stale rejection, assignment, immediate grant/revoke/disable, escalation denial, invalid permission rejection')
finally:
    if user:
        sql("DELETE FROM platform_audit WHERE target_id='"+user['id']+"'; DELETE FROM spring_session WHERE principal_name='"+prefix+"'; DELETE FROM platform_user WHERE id='"+user['id']+"'")
    if role:
        sql("DELETE FROM platform_audit WHERE target_id='"+role['id']+"'; DELETE FROM platform_role WHERE id='"+role['id']+"'")
