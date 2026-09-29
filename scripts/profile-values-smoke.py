from smoke_support import *
import uuid
uid=str(uuid.uuid4());username='qa_values_'+uid.replace('-','');changed=False
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
dictionary=str(uuid.uuid4());root=str(uuid.uuid4());item=str(uuid.uuid4());code='qa_profile_'+dictionary.replace('-','')
path='/platform/settings/profile-fields'
try:
    initial=json.loads(call(admin,path)[1]);assert initial['version']==0,'Refuse to overwrite existing schema'
    field={'key':'qualification','label':'Qualification','description':'','type':'SELECT','visible':True,'required':True,'weight':5,'placeholder':'','groupKey':'custom','groupLabel':'Custom','sortNo':100,'custom':True,'options':['One','Two']}
    data={'fields':initial['data']['fields']+[field]}
    assert write(admin,path,'PUT',{'version':0,'data':data,'changeReason':'Values fixture'})[0]==200;changed=True
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{uid}','{username}','Values QA',password_hash FROM platform_user WHERE username='admin'")
    actor=client();assert login(actor,username,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    endpoint='/account/profile-values';assert call(client(),endpoint)[0]==401
    current=json.loads(call(actor,endpoint)[1]);assert current['schemaVersion']==1 and current['values']=={}
    assert write(actor,endpoint,'PUT',{'version':0,'schemaVersion':1,'values':{}})[0]==400
    assert write(actor,endpoint,'PUT',{'version':0,'schemaVersion':1,'values':{'qualification':'Bad'}})[0]==400
    status,body=write(actor,endpoint,'PUT',{'version':0,'schemaVersion':1,'values':{'qualification':'One'}});assert status==200,(status,body)
    assert json.loads(body)['version']==1 and json.loads(body)['values']=={'qualification':'One'}
    assert json.loads(call(admin,endpoint)[1])['values']=={},'Cross-account values leaked'
    assert write(actor,endpoint,'PUT',{'version':0,'schemaVersion':1,'values':{'qualification':'Two'}})[0]==409
    field['visible']=False
    assert write(admin,path,'PUT',{'version':1,'data':data,'changeReason':'Hide field'})[0]==200
    assert write(actor,endpoint,'PUT',{'version':1,'schemaVersion':1,'values':{'qualification':'Two'}})[0]==409
    hidden=json.loads(call(actor,endpoint)[1]);assert hidden['values']=={} and hidden['fields']==[]
    assert write(actor,endpoint,'PUT',{'version':1,'schemaVersion':2,'values':{'qualification':'Two'}})[0]==400
    assert write(actor,endpoint,'PUT',{'version':1,'schemaVersion':2,'values':{}})[0]==200
    assert write(admin,path+'/rollback','POST',{'version':2,'sourceVersion':1,'changeReason':'Restore field'})[0]==200
    restored=json.loads(call(actor,endpoint)[1]);assert restored['values']=={'qualification':'One'} and restored['schemaVersion']==3
    assert sql(f"SELECT count(*) FROM platform_audit WHERE target_id='{uid}' AND action='profile.values'")=='2'
    sql(f"INSERT INTO platform_dictionary(id,code,name,structure) VALUES('{dictionary}','{code}','Profile dictionary','TREE'); INSERT INTO platform_dictionary_item(id,dictionary_id,value,label) VALUES('{root}','{dictionary}','root','Root'); INSERT INTO platform_dictionary_item(id,dictionary_id,parent_id,value,label) VALUES('{item}','{dictionary}','{root}','child','Child')")
    field.update(visible=True,options=[],dictionaryCode=code)
    data['fields'].append({**field,'key':'alternate','required':False})
    assert write(admin,path,'PUT',{'version':3,'data':data,'changeReason':'Dictionary binding'})[0]==200
    assert write(actor,endpoint,'PUT',{'version':2,'schemaVersion':4,'values':{'qualification':str(uuid.uuid4())}})[0]==400
    assert write(actor,endpoint,'PUT',{'version':2,'schemaVersion':4,'values':{'qualification':item,'alternate':root}})[0]==200
    score=json.loads(call(actor,'/account/profile-completion')[1])['score'];assert score['percent']==50
    itemEndpoint='/platform/dictionaries/'+dictionary+'/items/'+item+'?version=0'
    status,body=write(admin,itemEndpoint,'DELETE',{});assert status==409 and json.loads(body)['detail']=='dictionaryItemReferenced'
    sql(f"UPDATE platform_dictionary_item SET parent_id='{item}' WHERE id='{root}'")
    assert json.loads(call(actor,'/account/profile-completion')[1])['score']['earnedWeight']==5
    assert write(actor,endpoint,'PUT',{'version':3,'schemaVersion':4,'values':{'qualification':item,'alternate':root}})[0]==400
    sql(f"UPDATE platform_dictionary_item SET parent_id=NULL,enabled=false WHERE id='{root}'")
    assert write(actor,endpoint,'PUT',{'version':3,'schemaVersion':4,'values':{'qualification':item}})[0]==400
    score=json.loads(call(actor,'/account/profile-completion')[1])['score'];assert score['earnedWeight']==5
    sql(f"UPDATE platform_dictionary SET enabled=false WHERE id='{dictionary}'")
    assert write(admin,path,'PUT',{'version':4,'data':data,'changeReason':'Disabled dictionary'})[0]==400
    sql(f"UPDATE platform_dictionary SET enabled=true WHERE id='{dictionary}'; UPDATE platform_dictionary_item SET enabled=true WHERE id='{root}'")
    field['visible']=False
    assert write(admin,path,'PUT',{'version':4,'data':data,'changeReason':'Hide referenced field'})[0]==200
    assert write(actor,endpoint,'PUT',{'version':3,'schemaVersion':5,'values':{}})[0]==200
    assert write(admin,itemEndpoint,'DELETE',{})[0]==409
    field.update(visible=True,required=False)
    assert write(admin,path,'PUT',{'version':5,'data':data,'changeReason':'Allow clearing reference'})[0]==200
    assert write(actor,endpoint,'PUT',{'version':4,'schemaVersion':6,'values':{}})[0]==200
    assert write(admin,itemEndpoint,'DELETE',{})[0]==204
    print('PASS: selected/hidden item deletion blocked, explicit clearing releases reference; dictionary UUID binding, unknown option and disabled ancestor rejection, disabled dictionary schema rejection; custom values validation, account isolation, user/schema version conflicts, hidden-field rejection and retained values after schema rollback, audit')
finally:
    sql(f"DELETE FROM platform_audit WHERE target_id='{uid}'; DELETE FROM spring_session WHERE principal_name='{username}'; DELETE FROM platform_user WHERE id='{uid}'")
    if changed:sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_setting WHERE section='profile-fields'")
    sql(f"DELETE FROM platform_dictionary_item WHERE id='{item}'; DELETE FROM platform_dictionary_item WHERE id='{root}'; DELETE FROM platform_dictionary WHERE id='{dictionary}'")
