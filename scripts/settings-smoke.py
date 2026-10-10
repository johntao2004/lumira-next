"""Versioned branding persistence; only runs against untouched local branding defaults."""
from smoke_support import *
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
path='/platform/settings/branding'; status,body=call(admin,path);assert status==200,(status,body)
original=json.loads(body)
assert original['version']==0,'Branding already configured; refusing to overwrite user settings'
created=None
try:
    payload={'version':0,'data':{**original['data'],'websiteName':'Branding QA'}}
    assert call(client(),path)[0] in (401,403)
    assert call(admin,path,'PUT',json.dumps(payload).encode(),{'Content-Type':'application/json'})[0]==403
    status,body=write(admin,path,'PUT',payload);assert status==200,(status,body)
    saved=json.loads(body);assert saved['version']==1 and saved['data']['websiteName']=='Branding QA'
    created=sql("SELECT id FROM platform_setting WHERE section='branding'")
    assert write(admin,path,'PUT',payload)[0]==409
    invalid={**payload,'version':1,'data':{**payload['data'],'websiteLogoUrl':'javascript:alert(1)'}}
    assert write(admin,path,'PUT',invalid)[0]==400
    status,body=write(admin,path,'PUT',{**payload,'version':1});assert status==200,(status,body)
    assert json.loads(body)['version']==2
    assert sql("SELECT count(*) FROM platform_setting_revision WHERE setting_id='"+created+"'")=='2'
    status,body=write(admin,path,'PUT',{**payload,'version':2,'data':original['data']});assert status==200
    assert json.loads(body)['version']==3
    assert sql("SELECT count(*) FROM platform_setting_revision WHERE setting_id='"+created+"'")=='3'
    assert sql("SELECT count(*) FROM platform_audit WHERE target_id='"+created+"' AND action='setting.update'")=='3'
    status,body=call(admin,path+'/history?page=1&pageSize=1');assert status==200
    history=json.loads(body);assert history['total']==3 and history['items'][0]['version']==3
    assert call(admin,path+'/history?page=0')[0]==400
    assert call(client(),path+'/history')[0] in (401,403)
    status,body=call(admin,path+'/history/1');assert status==200 and json.loads(body)['data']['websiteName']=='Branding QA'
    rollback={'version':3,'sourceVersion':1}
    assert write(admin,path+'/rollback','POST',{**rollback,'sourceVersion':999})[0]==404
    status,body=write(admin,path+'/rollback','POST',rollback);assert status==200,(status,body)
    restored=json.loads(body);assert restored['version']==4 and restored['data']['websiteName']=='Branding QA'
    assert write(admin,path+'/rollback','POST',rollback)[0]==409
    status,body=call(admin,path+'/history');assert status==200
    history=json.loads(body);assert history['total']==4 and history['items'][0]['sourceVersion']==1
    assert call(admin,path+'/history/2')[0]==200
    print('PASS: branding defaults, CSRF, anonymous denial, version conflicts, invalid rollback, revision and audit atomicity')
finally:
    if created:sql("DELETE FROM platform_setting_revision WHERE setting_id='"+created+"'; DELETE FROM platform_audit WHERE target_id='"+created+"'; DELETE FROM platform_setting WHERE id='"+created+"'")
