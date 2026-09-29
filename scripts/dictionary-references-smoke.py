from smoke_support import *
import uuid
id=str(uuid.uuid4());code='qa_reference_'+id.replace('-','');changed=False
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
path='/platform/settings/profile-fields'
try:
    initial=json.loads(call(admin,path)[1]);assert initial['version']==0,'Refuse to overwrite configured schema'
    sql(f"INSERT INTO platform_dictionary(id,code,name,structure) VALUES('{id}','{code}','Reference QA','FLAT')")
    field={'key':'choice','label':'Choice','description':'','type':'SELECT','visible':False,'required':False,'weight':5,'placeholder':'','groupKey':'custom','groupLabel':'Custom','sortNo':100,'custom':True,'options':[],'dictionaryCode':code}
    assert write(admin,path,'PUT',{'version':0,'data':{'fields':initial['data']['fields']+[field]},'changeReason':'Reference fixture'})[0]==200;changed=True
    endpoint='/platform/dictionaries/'+id
    assert write(admin,endpoint+'?version=99','DELETE',{})[0]==409
    status,body=write(admin,endpoint+'?version=0','DELETE',{});assert status==409 and json.loads(body)['detail']=='dictionaryReferenced',(status,body)
    assert call(admin,endpoint)[0]==200
    assert sql(f"SELECT count(*) FROM platform_audit WHERE target_id='{id}' AND action='dictionary.delete'")=='0'
    assert write(admin,path,'PUT',{'version':1,'data':initial['data'],'changeReason':'Unbind'})[0]==200
    assert write(admin,endpoint+'?version=0','DELETE',{})[0]==204
    assert call(admin,endpoint)[0]==404
    assert write(admin,path+'/rollback','POST',{'version':2,'sourceVersion':1,'changeReason':'Unavailable reference'})[0]==400
    assert json.loads(call(admin,path)[1])['version']==2
    print('PASS: hidden profile binding protects empty dictionary deletion, stale version guard, no rejected-delete audit, unbind permits deletion, rollback rejects missing dictionary')
finally:
    if changed:sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_setting WHERE section='profile-fields'")
    sql(f"DELETE FROM platform_audit WHERE target_id='{id}'; DELETE FROM platform_dictionary WHERE id='{id}'")
