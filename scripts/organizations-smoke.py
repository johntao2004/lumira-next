"""Verify organization hierarchy and rollback on isolated fixtures."""
from smoke_support import *
import uuid,secrets
prefix='qa_org_'+uuid.uuid4().hex
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
created=[]
user=None
try:
    disabled_data={'code':prefix+'_disabled','name':'Disabled QA','parentId':None,'enabled':False}
    status,body=write(admin,'/platform/organizations','POST',disabled_data);assert status==201,(status,body)
    disabled=json.loads(body);created.append(disabled['id'])
    status,body=call(admin,'/platform/organizations/'+disabled['id']);assert status==200 and json.loads(body)['enabled'] is False
    status,body=call(admin,'/platform/organizations?search='+prefix);assert status==200
    assert any(row['id']==disabled['id'] and row['enabled'] is False for row in json.loads(body)['items'])
    assert write(admin,'/platform/organizations/'+disabled['id']+'?version=0','DELETE',{})[0]==204
    created.remove(disabled['id'])

    data={'code':prefix,'name':'Root QA','parentId':None,'enabled':True}
    status,body=write(admin,'/platform/organizations','POST',data);assert status==201,(status,body)
    root=json.loads(body);created.append(root['id'])
    assert write(admin,'/platform/organizations','POST',data)[0]==409
    child_data={**data,'code':prefix+'_child','name':'Child QA','parentId':root['id']}
    status,body=write(admin,'/platform/organizations','POST',child_data);assert status==201,(status,body)
    child=json.loads(body);created.append(child['id'])
    status,body=call(admin,'/platform/organizations?parent=root&search='+prefix);assert status==200
    assert [row['id'] for row in json.loads(body)['items']]==[root['id']]
    status,body=call(admin,'/platform/organizations?parent='+root['id']+'&pageSize=1');assert status==200
    assert json.loads(body)['total']==1 and json.loads(body)['items'][0]['id']==child['id']
    status,body=call(admin,'/platform/organizations?parent='+root['id']+'&page=2&pageSize=1');assert status==200 and json.loads(body)['items']==[] and json.loads(body)['total']==1
    status,body=call(admin,'/platform/organizations/'+child['id']+'/ancestors');assert status==200,(status,body)
    assert [row['id'] for row in json.loads(body)]==[root['id'],child['id']]
    assert call(client(),'/platform/organizations/'+child['id']+'/ancestors')[0] in (401,403)
    assert call(admin,'/platform/organizations?parent=bad')[0]==400
    assert call(admin,'/platform/organizations?parent='+str(uuid.uuid4()))[0]==404

    assert write(admin,'/platform/organizations/'+root['id'],'PUT',{**data,'parentId':child['id'],'version':0})[0]==409
    assert write(admin,'/platform/organizations/'+root['id'],'PUT',{**data,'parentId':root['id'],'version':0})[0]==409
    assert write(admin,'/platform/organizations/'+root['id']+'?version=0','DELETE',{})[0]==409
    status,body=call(admin,'/platform/organizations/'+root['id']);assert json.loads(body)['version']==0 and json.loads(body)['parentId'] is None
    status,body=write(admin,'/platform/organizations/'+child['id'],'PUT',{**child_data,'name':'Moved','parentId':None,'version':0});assert status==200,(status,body)
    assert write(admin,'/platform/organizations/'+child['id'],'PUT',{**child_data,'version':0})[0]==409
    assert write(admin,'/platform/organizations/'+child['id']+'?version=0','DELETE',{})[0]==409
    status,body=call(admin,'/platform/organizations?search='+prefix);assert status==200 and json.loads(body)['total']==2
    status,body=write(admin,'/platform/users','POST',{'username':prefix,'displayName':'Membership QA','password':secrets.token_urlsafe(24)});assert status==201
    user=json.loads(body)
    path='/platform/users/'+user['id']+'/organizations'
    assert write(admin,path,'PUT',{'version':0,'organizationIds':[child['id']]})[0]==200
    status,body=call(admin,path);assert status==200 and json.loads(body)['organizationIds']==[child['id']]
    assert write(admin,'/platform/organizations/'+child['id']+'?version=1','DELETE',{})[0]==409
    assert write(admin,path,'PUT',{'version':0,'organizationIds':[]})[0]==409
    assert write(admin,path,'PUT',{'version':1,'organizationIds':[str(uuid.uuid4())]})[0]==404
    status,body=call(admin,path);assert json.loads(body)['version']==1 and json.loads(body)['organizationIds']==[child['id']]
    assert write(admin,path,'PUT',{'version':1,'organizationIds':[]})[0]==200
    for id,version in [(child['id'],1),(root['id'],0)]:
        assert write(admin,'/platform/organizations/'+id+'?version='+str(version),'DELETE',{})[0]==204
        assert call(admin,'/platform/organizations/'+id)[0]==404
    print('PASS: hierarchy filtering/pagination/ancestors, membership assignment, stale/missing rejection, membership deletion protection, rollback, organization creation, duplicate, parent/self cycles, child deletion protection, failed-write rollback, move, stale version, search, delete')
finally:
    if user:sql("DELETE FROM platform_audit WHERE target_id='"+user['id']+"'; DELETE FROM platform_user WHERE id='"+user['id']+"'")
    for id in reversed(created):sql("DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM platform_organization WHERE id='"+id+"'")
