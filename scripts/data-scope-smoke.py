"""Real-session user data scopes, independent of unrelated permissions."""
from smoke_support import *
import uuid
prefix='qa_scope_'+uuid.uuid4().hex
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
ids={name:str(uuid.uuid4()) for name in ['actor','peer','child','outside','root','sub','other']}
roles=[]
try:
    for name in ['actor','peer','child','outside']:
        sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '"+ids[name]+"','"+prefix+'_'+name+"','Scope QA',password_hash FROM platform_user WHERE username='admin'")
    for name,parent in [('root',None),('sub','root'),('other',None)]:
        sql("INSERT INTO platform_organization(id,code,name,parent_id) VALUES('"+ids[name]+"','"+prefix+'_'+name+"','Scope organization',"+("'"+ids[parent]+"'" if parent else 'NULL')+")")
    for name,org in [('actor','root'),('peer','root'),('child','sub'),('outside','other')]:
        sql("INSERT INTO platform_user_organization(user_id,organization_id) VALUES('"+ids[name]+"','"+ids[org]+"')")
    payload={'code':prefix,'name':'Scoped users','enabled':True,'authorities':['platform:users:read','platform:users:write','platform:organizations:read'],'dataScope':'SELF'}
    status,body=write(admin,'/platform/roles','POST',payload);assert status==201,(status,body)
    role=json.loads(body);roles.append(role['id'])
    assert write(admin,'/platform/users/'+ids['actor']+'/roles','PUT',{'version':0,'roleIds':roles})[0]==200
    limited=client();assert login(limited,prefix+'_actor',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    def visible():
        status,body=call(limited,'/platform/users?search='+prefix);assert status==200
        page=json.loads(body);assert page['total']==len(page['items']);return {r['id'] for r in page['items']}
    def visible_orgs():
        status,body=call(limited,'/platform/organizations?search='+prefix);assert status==200,(status,body)
        page=json.loads(body);assert page['total']==len(page['items']);return {r['id'] for r in page['items']}
    def visible_export():
        status,body=call(limited,'/platform/users/export?search='+prefix);assert status==200,(status,body)
        rows=[line for line in body.decode('utf-8-sig').splitlines() if line]
        assert rows[0]=='id,username,display_name,enabled,version,file_quota_bytes_override,file_quota_files_override'
        return {line.split(',',1)[0] for line in rows[1:]}
    assert visible()=={ids['actor']}
    assert visible_export()=={ids['actor']}
    assert visible_orgs()=={ids['root']}
    assert write(limited,'/platform/users/'+ids['outside'],'PUT',{'displayName':'Forbidden','enabled':True,'version':0})[0]==403
    for version,scope,names,orgs in [(0,'ORGANIZATION',['actor','peer'],['root']),(1,'SUBTREE',['actor','peer','child'],['root','sub']),(2,'ALL',['actor','peer','child','outside'],['root','sub','other']),(3,'SELF',['actor'],['root'])]:
        status,body=write(admin,'/platform/roles/'+role['id'],'PUT',{**payload,'version':version,'dataScope':scope});assert status==200,(status,body)
        assert visible()=={ids[n] for n in names},scope
        assert visible_export()=={ids[n] for n in names},scope
        assert visible_orgs()=={ids[n] for n in orgs},scope
    assert call(limited,'/platform/organizations/'+ids['other'])[0]==404
    status,body=write(admin,'/platform/roles','POST',{'code':prefix+'_unrelated','name':'Global modules','enabled':True,'authorities':['platform:modules:read'],'dataScope':'ALL'});assert status==201
    roles.append(json.loads(body)['id'])
    assert write(admin,'/platform/users/'+ids['actor']+'/roles','PUT',{'version':1,'roleIds':roles})[0]==200
    assert visible()=={ids['actor']},'Unrelated global role widened users'
    assert call(limited,'/platform/modules')[0]==200
    assert write(limited,'/platform/users/'+ids['actor'],'PUT',{'displayName':'Self updated','enabled':True,'version':2})[0]==200
    print('PASS: SELF/ORGANIZATION/SUBTREE/ALL user, export and organization visibility, immediate scope changes, hidden organization detail rejection, foreign user write denied, self write allowed, unrelated global grant isolation')
finally:
    for name in ['actor','peer','child','outside']:
        sql("DELETE FROM platform_audit WHERE target_id='"+ids[name]+"'; DELETE FROM spring_session WHERE principal_name='"+prefix+'_'+name+"'; DELETE FROM platform_user WHERE id='"+ids[name]+"'")
    for id in roles:sql("DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM platform_role WHERE id='"+id+"'")
    for name in ['sub','root','other']:sql("DELETE FROM platform_organization WHERE id='"+ids[name]+"'")
