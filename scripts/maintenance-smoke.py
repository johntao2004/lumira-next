"""Maintenance login policy with administrator recovery and session rejection."""
from smoke_support import *
import uuid
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
path='/platform/settings/maintenance';status,body=call(admin,path);assert status==200,(status,body)
assert json.loads(body)['version']==0,'Refuse to overwrite configured maintenance'
name='qa_maintenance_'+uuid.uuid4().hex;created=None;role=None
try:
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+name+"','Maintenance QA',password_hash FROM platform_user WHERE username='admin'")
    data={'enabled':True,'title':'Maintenance QA','message':'Testing login policy','endAt':None,'allowedRoleIds':[]}
    status,body=write(admin,path,'PUT',{'version':0,'data':data,'changeReason':'Maintenance test'});assert status==200,(status,body)
    created=sql("SELECT id FROM platform_setting WHERE section='maintenance'")
    denied=client();assert login(denied,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==503
    assert call(denied,'/auth/me')[0]==401
    assert login(client(),'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    status,body=call(client(),'/public/maintenance');assert status==200
    public=json.loads(body);assert public['active'] and 'allowedRoleIds' not in public
    status,body=write(admin,'/platform/roles','POST',{'code':name,'name':'Maintenance role','enabled':True,'dataScope':'SELF','authorities':[]});assert status==201,(status,body)
    role=json.loads(body)['id']
    sql("INSERT INTO platform_user_role(user_id,role_id) SELECT id,'"+role+"' FROM platform_user WHERE username='"+name+"'")
    data={**data,'allowedRoleIds':[role]}
    assert write(admin,path,'PUT',{'version':1,'data':data,'changeReason':'Allow role'})[0]==200
    assert login(client(),name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    reader=client();assert login(reader,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert call(reader,path+'/roles')[0]==403
    sql("INSERT INTO platform_authority(user_id,authority) SELECT id,'platform:settings:read' FROM platform_user WHERE username='"+name+"'")
    status,body=call(reader,path+'/roles?page=1&pageSize=1&search='+name);assert status==200,(status,body)
    options=json.loads(body);assert options['total']==1 and len(options['items'])==1
    assert set(options['items'][0])=={'id','name','enabled'}
    assert call(reader,'/platform/roles')[0]==403
    assert call(reader,path+'/roles?pageSize=101')[0]==400
    assert write(reader,path,'PUT',{'version':2,'data':data,'changeReason':'No write grant'})[0]==403
    sql("UPDATE platform_role SET enabled=false WHERE id='"+role+"'")
    assert login(client(),name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==503
    data={**data,'allowedRoleIds':[],'endAt':'2000-01-01T00:00:00Z'}
    assert write(admin,path,'PUT',{'version':2,'data':data,'changeReason':'Expired window'})[0]==200
    assert login(client(),name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    print('PASS: settings-only minimal role lookup and role-management denial, maintenance denial clears session, global admin recovery, allowed role, disabled role rejection and expired-window login')
finally:
    if created:sql("DELETE FROM platform_setting_revision WHERE setting_id='"+created+"'; DELETE FROM platform_audit WHERE target_id='"+created+"'; DELETE FROM platform_setting WHERE id='"+created+"'")
    sql("DELETE FROM spring_session WHERE principal_name='"+name+"'; DELETE FROM platform_authority WHERE user_id IN (SELECT id FROM platform_user WHERE username='"+name+"'); DELETE FROM platform_user_role WHERE user_id IN (SELECT id FROM platform_user WHERE username='"+name+"'); DELETE FROM platform_user WHERE username='"+name+"'")
    if role:sql("DELETE FROM platform_role_authority WHERE role_id='"+role+"'; DELETE FROM platform_audit WHERE target_id='"+role+"'; DELETE FROM platform_role WHERE id='"+role+"'")
