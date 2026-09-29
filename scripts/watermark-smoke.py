"""Watermark settings validation/version/public read, isolated from existing configurations."""
from smoke_support import *
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
path='/platform/settings/watermark';status,body=call(admin,path);assert status==200,(status,body)
initial=json.loads(body);assert initial['version']==0,'Refuse to replace configured watermark';created=None
try:
    data={**initial['data'],'enabled':True,'personalizedTextLines':['{{username}} / {{realName}}']}
    payload={'version':0,'data':data,'changeReason':'Watermark verification'}
    assert call(client(),path)[0] in (401,403)
    assert call(admin,path,'PUT',json.dumps(payload).encode(),{'Content-Type':'application/json'})[0]==403
    status,body=write(admin,path,'PUT',payload);assert status==200,(status,body)
    created=sql("SELECT id FROM platform_setting WHERE section='watermark'")
    assert json.loads(body)['version']==1
    assert write(admin,path,'PUT',payload)[0]==409
    for patch in [{'personalizedTextLines':['{{password}}']},{'opacity':2},{'mode':'IMAGE','imageUrl':'javascript:alert(1)'},{'gapX':-1}]:
        assert write(admin,path,'PUT',{**payload,'version':1,'data':{**data,**patch}})[0]==400
    status,body=call(client(),'/public/watermark');assert status==200 and json.loads(body)==data
    status,body=call(admin,path+'/history');assert status==200 and json.loads(body)['total']==1
    assert sql("SELECT count(*) FROM platform_audit WHERE target_id='"+created+"'")=='1'
    print('PASS: watermark CSRF/auth, template/URL/numeric validation, stale writes, public data and single revision after rejected edits')
finally:
    if created:sql("DELETE FROM platform_setting_revision WHERE setting_id='"+created+"'; DELETE FROM platform_audit WHERE target_id='"+created+"'; DELETE FROM platform_setting WHERE id='"+created+"'")
