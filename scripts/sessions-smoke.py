"""Own-session listing and revocation must never cross account boundaries."""
from smoke_support import *
import uuid
prefix='qa_sessions_'+uuid.uuid4().hex
password=config['LUMIRA_BOOTSTRAP_PASSWORD']
try:
    for name in [prefix,prefix+'_other']:
        sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+name+"','Sessions QA',password_hash FROM platform_user WHERE username='admin'")
    first,second,foreign=client(),client(),client()
    assert login(first,prefix,password)==200 and login(second,prefix,password)==200 and login(foreign,prefix+'_other',password)==200
    def listing(c):
        status,body=call(c,'/account/sessions');assert status==200
        return json.loads(body)
    own=listing(first);assert own['items'][0]['current'];assert own['total']==2 and sum(s['current'] for s in own['items'])==1
    assert set(own['items'][0])=={'id','createdAt','lastAccessAt','expiresAt','current'}
    other=listing(foreign)['items'][0]
    assert write(first,'/account/sessions/'+other['id'],'DELETE',{})[0]==404
    assert call(foreign,'/auth/me')[0]==200
    second_id=next(s['id'] for s in own['items'] if not s['current'])
    assert write(first,'/account/sessions/'+second_id,'DELETE',{})[0]==204
    assert call(second,'/auth/me')[0]==401
    assert call(first,'/auth/me')[0]==200
    second,third=client(),client()
    assert login(second,prefix,password)==200 and login(third,prefix,password)==200
    assert write(first,'/account/sessions/others','DELETE',{})[0]==204
    assert call(second,'/auth/me')[0]==401 and call(third,'/auth/me')[0]==401
    assert call(foreign,'/auth/me')[0]==200
    own=listing(first);assert own['total']==1 and own['items'][0]['current']
    assert write(first,'/account/sessions/'+own['items'][0]['id'],'DELETE',{})[0]==204
    assert call(first,'/auth/me')[0]==401
    assert call(client(),'/account/sessions')[0]==401
    print('PASS: safe session fields, current marker, foreign account isolation, individual/other/current revocation, replay rejected')
finally:
    for name in [prefix,prefix+'_other']:
        sql("DELETE FROM platform_audit WHERE actor='"+name+"'; DELETE FROM spring_session WHERE principal_name='"+name+"'; DELETE FROM platform_user WHERE username='"+name+"'")
