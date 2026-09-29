"""Only terminal fixture rows: never enqueue messages or invoke SMTP."""
from smoke_support import *
from uuid import uuid4,UUID
prefix=uuid4().hex[:8];name='qa_mail_query_'+uuid4().hex;user=str(uuid4());role=str(uuid4());ids=[]
actor=client();path='/platform/mail-deliveries'
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{user}','{name}','Mail query QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert call(client(),path)[0] in (401,403) and call(actor,path)[0]==403
    sql(f"INSERT INTO platform_role(id,code,name,data_scope) VALUES('{role}','{name}','Scoped audit','SELF'); INSERT INTO platform_role_authority(role_id,authority) VALUES('{role}','platform:audit:read'); INSERT INTO platform_user_role(user_id,role_id) VALUES('{user}','{role}')")
    assert call(actor,path)[0]==403
    sql(f"UPDATE platform_role SET data_scope='ALL' WHERE id='{role}'")
    for i in range(105):
        id=str(UUID(prefix+uuid4().hex[8:]));ids.append(id)
        sql(f"INSERT INTO platform_mail_delivery(id,payload,state,attempts,expires_at,last_result) VALUES('{id}','v1:fixture:abcdef','{'FAILED' if i<100 else 'ACCEPTED'}',1,now()+interval '1 hour','{'AUTHENTICATION_FAILED' if i<100 else 'ACCEPTED'}')")
    def page(query):
        status,body=call(actor,path+'?search='+prefix+'&'+query);assert status==200,status
        return json.loads(body)
    one=page('pageSize=100');two=page('page=2&pageSize=100')
    assert one['total']==105 and len(one['items'])==100 and len(two['items'])==5
    assert not ({r['id'] for r in one['items']}&{r['id'] for r in two['items']})
    expected={'id','state','attempts','lastResult','availableAt','expiresAt','createdAt','updatedAt'}
    assert all(set(r)==expected for r in one['items']+two['items'])
    assert page('state=FAILED')['total']==100 and page('state=ACCEPTED')['total']==5
    for query in ['page=0','pageSize=101','state=unknown']:
        assert call(actor,path+'?'+query)[0]==400
    assert json.loads(call(actor,path+'?search=%25')[1])['total']==0
    sql(f"UPDATE platform_role SET data_scope='SELF' WHERE id='{role}'")
    assert call(actor,path)[0]==403
    print('PASS: mail metadata permission/global scope, immediate revocation, 105-row pagination, state filtering, safe fields and bounded queries; no messages sent')
finally:
    if ids:sql('DELETE FROM platform_mail_delivery WHERE id IN ('+','.join("'"+i+"'" for i in ids)+')')
    sql(f"DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{user}'; DELETE FROM platform_role WHERE id='{role}'")
