from smoke_support import *
import uuid
id=str(uuid.uuid4());name='qa_score_'+id.replace('-','');changed=False
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
path='/platform/settings/profile-fields'
try:
    initial=json.loads(call(admin,path)[1]);assert initial['version']==0
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{id}','{name}','Score QA',password_hash FROM platform_user WHERE username='admin'")
    actor=client();assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    endpoint='/account/profile-completion';assert call(client(),endpoint)[0]==401
    first=json.loads(call(actor,endpoint)[1]);assert first['score']['percent']==25 and first['score']['totalWeight']==20
    custom={'key':'scoreNumber','label':'Number','description':'','type':'NUMBER','visible':True,'required':False,'weight':80,'placeholder':'','groupKey':'custom','groupLabel':'Custom','sortNo':100,'custom':True,'options':[]}
    data={'fields':initial['data']['fields']+[custom]}
    assert write(admin,path,'PUT',{'version':0,'data':data,'changeReason':'Completion fixture'})[0]==200;changed=True
    second=json.loads(call(actor,endpoint)[1]);assert second['score']['percent']==5 and second['schemaVersion']==1
    assert write(actor,'/account/profile-values','PUT',{'version':0,'schemaVersion':1,'values':{'scoreNumber':'0'}})[0]==200
    third=json.loads(call(actor,endpoint)[1]);assert third['score']['percent']==85 and third['version']==1
    assert third['score']['groups'][1]['percent']==100
    for f in data['fields']:
        if f['key'] in ['realName','birthDate','region']:f['visible']=False
    assert write(admin,path,'PUT',{'version':1,'data':data,'changeReason':'Hide incomplete fields'})[0]==200
    assert json.loads(call(actor,endpoint)[1])['score']['percent']==100
    custom.update(type='SELECT',options=['One'])
    assert write(admin,path,'PUT',{'version':2,'data':data,'changeReason':'Invalidate previous type'})[0]==200
    final=json.loads(call(actor,endpoint)[1]);assert final['score']['earnedWeight']==5 and not final['score']['groups'][1]['items'][0]['completed']
    assert all('value' not in item for group in final['score']['groups'] for item in group['items'])
    custom.update(type='NUMBER',options=[],weight=100)
    for field in data['fields']:
        if field['key']=='displayName':field['weight']=100
    data['fields'].append(dict(custom,key='smallMissing',label='Small missing',type='TEXT',weight=1))
    assert write(admin,path,'PUT',{'version':3,'data':data,'changeReason':'Rounding boundary'})[0]==200
    boundary=json.loads(call(actor,endpoint)[1])
    assert boundary['score']['percent']==99 and boundary['score']['earnedWeight']==200 and boundary['score']['totalWeight']==201
    assert write(actor,'/account/profile-values','PUT',{'version':boundary['version'],'schemaVersion':4,'values':{'scoreNumber':'0','smallMissing':'Done'}})[0]==200
    assert json.loads(call(actor,endpoint)[1])['score']['percent']==100
    print('PASS: weighted completion, zero numeric value counts, groups, hidden fields excluded, schema/type changes, rounding boundary and true completion, no raw profile values')
finally:
    sql(f"DELETE FROM platform_audit WHERE target_id='{id}'; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{id}'")
    if changed:sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_setting WHERE section='profile-fields'")
