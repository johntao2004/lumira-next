from smoke_support import *
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
path='/platform/settings/profile-fields';changed=False
try:
    assert call(client(),path)[0]==401
    assert call(client(),'/account/profile-fields')[0]==401
    status,body=call(admin,path);assert status==200
    initial=json.loads(body);assert initial['version']==0,'Refuse to overwrite existing schema'
    field={'key':'qualification','label':'Qualification','description':'','type':'SELECT','visible':True,'required':False,'weight':5,'placeholder':'Choose','groupKey':'custom','groupLabel':'Custom','sortNo':100,'custom':True,'options':['One','Two']}
    data={'fields':initial['data']['fields']+[field]}
    status,body=write(admin,path,'PUT',{'version':0,'data':data,'changeReason':'Schema fixture'});assert status==200,(status,body);changed=True
    assert json.loads(body)['version']==1
    assert json.loads(call(admin,'/account/profile-fields')[1])['data']==data
    assert write(admin,path,'PUT',{'version':0,'data':data,'changeReason':'Stale'})[0]==409
    for invalid in [dict(field,key='password'),dict(field,options=['One','One']),dict(field,type='CODE')]:
        assert write(admin,path,'PUT',{'version':1,'data':{'fields':initial['data']['fields']+[invalid]},'changeReason':'Invalid'})[0]==400
    assert json.loads(call(admin,path+'/history')[1])['total']==1
    status,body=write(admin,path,'PUT',{'version':1,'data':initial['data'],'changeReason':'Defaults'});assert status==200
    status,body=write(admin,path+'/rollback','POST',{'version':2,'sourceVersion':1,'changeReason':'Restore schema'});assert status==200
    assert json.loads(body)['version']==3 and json.loads(body)['data']==data
    assert write(admin,path+'/rollback','POST',{'version':2,'sourceVersion':1,'changeReason':'Stale'})[0]==409
    print('PASS: profile schema defaults, authenticated schema read, save, invalid/reserved fields rejected, stale writes, history and rollback')
finally:
    if changed:sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_setting WHERE section='profile-fields'")
