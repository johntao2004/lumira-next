"""Exercise password changes on an isolated temporary account, never on admin."""
import json, uuid, secrets
from smoke_support import config, sql, client, token, call as request_call, login as login_account
username = 'qa_password_' + uuid.uuid4().hex
password = config['LUMIRA_BOOTSTRAP_PASSWORD']
new_password = secrets.token_urlsafe(24)
def call(c, path, data=None, headers=None):
    return request_call(c, path, 'POST' if data is not None else 'GET', data, headers)
def login(c, secret): return login_account(c, username, secret)
def change(c, old, new): return call(c,'/account/password',json.dumps({'currentPassword':old,'newPassword':new}).encode(),{**token(c),'Content-Type':'application/json'})[0]
try:
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+username+"','Password QA',password_hash FROM platform_user WHERE username='admin'")
    first,second=client(),client()
    assert login(first,password)==200 and login(second,password)==200
    assert change(first,'incorrect-current-password',new_password)==400
    assert change(first,'密'*86,new_password)==400
    assert change(first,password,'short')==400
    assert change(first,password,password)==400
    changed = change(first,password,new_password)
    assert changed==204, ('password change status', changed)
    assert call(first,'/auth/me')[0]==401
    assert call(second,'/auth/me')[0]==401
    assert login(client(),password)==401
    assert login(client(),new_password)==200
    print('PASS: wrong current password, policy, reuse rejection, password change, both sessions revoked, old password rejected, new login accepted')
finally:
    sql("DELETE FROM platform_audit WHERE actor='"+username+"'; DELETE FROM spring_session WHERE principal_name='"+username+"'; DELETE FROM platform_user WHERE username='"+username+"'")
