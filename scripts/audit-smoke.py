"""Audit pagination and independent authorization with isolated log fixtures."""
from smoke_support import *
import uuid
prefix='qa_audit_'+uuid.uuid4().hex
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
try:
    sql("INSERT INTO platform_audit(id,actor,action,target_id) SELECT gen_random_uuid(),'"+prefix+"','user.update',gen_random_uuid() FROM generate_series(1,105)")
    status,body=call(admin,'/platform/audit?search='+prefix+'&pageSize=100');a=json.loads(body);assert status==200 and a['total']==105 and len(a['items'])==100
    status,body=call(admin,'/platform/audit?search='+prefix+'&pageSize=100&page=2');b=json.loads(body);assert status==200 and len(b['items'])==5
    assert not ({e['id'] for e in a['items']} & {e['id'] for e in b['items']})
    assert set(a['items'][0])=={'id','actor','action','targetId','createdAt'}
    assert call(admin,'/platform/audit?pageSize=101')[0]==400
    assert call(client(),'/platform/audit')[0]==401
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+prefix+"','Audit QA',password_hash FROM platform_user WHERE username='admin'")
    c=client();assert login(c,prefix,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert call(c,'/platform/audit')[0]==403
    assert write(admin,'/platform/audit','DELETE',{})[0]==403
    print('PASS: audit pagination, nonoverlap, search, fields, bounds, anonymous/unauthorized denial, read-only API')
finally:
    sql("DELETE FROM platform_audit WHERE actor='"+prefix+"'; DELETE FROM spring_session WHERE principal_name='"+prefix+"'; DELETE FROM platform_user WHERE username='"+prefix+"'")
