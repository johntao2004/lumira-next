from smoke_support import *
import uuid
id=str(uuid.uuid4());name='qa_builtin_'+id.replace('-','');changed=False
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
path='/platform/settings/profile-fields'
try:
    initial=json.loads(call(admin,path)[1]);assert initial['version']==0
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash,real_name) SELECT '{id}','{name}','Builtin QA',password_hash,'Retained name' FROM platform_user WHERE username='admin'")
    actor=client();assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    data=initial['data']
    for field in data['fields']:
        if field['key']=='realName':field['visible']=False
        if field['key']=='region':field.update(required=True,label='Location QA',sortNo=0)
    assert write(admin,path,'PUT',{'version':0,'data':data,'changeReason':'Builtin fixture'})[0]==200;changed=True
    profile=json.loads(call(actor,'/account/profile')[1]);assert profile['schemaVersion']==1
    assert profile['fields'][0]['key']=='region' and not any(f['key']=='realName' for f in profile['fields'])
    values={'displayName':'Builtin QA','region':'Here','version':0,'schemaVersion':1}
    assert write(actor,'/account/profile','PUT',{**values,'realName':'Overwrite hidden'})[0]==400
    assert write(actor,'/account/profile','PUT',{**values,'region':''})[0]==400
    assert write(actor,'/account/profile','PUT',{**values,'schemaVersion':0})[0]==409
    status,body=write(actor,'/account/profile','PUT',values);assert status==200,(status,body)
    assert json.loads(body)['realName']=='Retained name' and json.loads(body)['version']==1
    for field in data['fields']:
        if field['key']=='realName':field['visible']=True
    assert write(admin,path,'PUT',{'version':1,'data':data,'changeReason':'Show retained field'})[0]==200
    assert write(actor,'/account/profile','PUT',{**values,'version':1})[0]==409
    profile=json.loads(call(actor,'/account/profile')[1]);assert profile['realName']=='Retained name' and any(f['key']=='realName' for f in profile['fields'])
    print('PASS: built-in visibility/required/order metadata, hidden write rejection and retention, schema conflict, restored visibility')
finally:
    sql(f"DELETE FROM platform_audit WHERE target_id='{id}'; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{id}'")
    if changed:sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_setting WHERE section='profile-fields'")
