"""Verify user administration using an isolated account and cleanup."""
import json, uuid, secrets
from smoke_support import config, sql, client, call as request_call, login as login_account, write as write_json
username = 'qa_password_' + uuid.uuid4().hex
password = config['LUMIRA_BOOTSTRAP_PASSWORD']
new_password = secrets.token_urlsafe(24)
def call(c, path, data=None, headers=None):
    return request_call(c, path, 'POST' if data is not None else 'GET', data, headers)
def login(c, secret): return login_account(c, username, secret)
test_username=username
username='admin'
admin=client()
assert login(admin,password)==200
def write(path,method,payload,c=admin):
    return write_json(c, path, method, payload)
created=None
try:
    status,body=write('/platform/users','POST',{'username':test_username,'displayName':'User QA','password':new_password})
    assert status==201,(status,body)
    created=json.loads(body)
    assert write('/platform/users','POST',{'username':test_username,'displayName':'Duplicate','password':new_password})[0]==409
    username=test_username
    limited=client();assert login(limited,new_password)==200
    assert write('/platform/users','POST',{'username':test_username+'x','displayName':'Forbidden','password':new_password},limited)[0]==403
    status,body=write('/platform/users/'+created['id'],'PUT',{'displayName':'Edited','enabled':True,'version':created['version']})
    assert status==200,(status,body)
    updated=json.loads(body)
    assert updated['displayName']=='Edited' and updated['version']==created['version']+1
    assert write('/platform/users/'+created['id'],'PUT',{'displayName':'Stale','enabled':True,'version':created['version']})[0]==409
    assert write('/platform/users/'+created['id'],'PUT',{'displayName':'Edited','enabled':False,'version':updated['version']})[0]==200
    assert call(limited,'/auth/me')[0]==401
    assert login(client(),new_password)==401
    status,body=call(admin,'/platform/users?search=admin')
    own=next(row for row in json.loads(body)['items'] if row['username']=='admin')
    assert write('/platform/users/'+own['id'],'PUT',{'displayName':own['displayName'],'enabled':False,'version':own['version']})[0]==409
    print('PASS: create, duplicate rejection, no implicit admin grants, edit, stale write conflict, disable/session revocation, self-admin protection')
finally:
    if created:
        sql("DELETE FROM platform_audit WHERE target_id='"+created['id']+"'; DELETE FROM spring_session WHERE principal_name='"+test_username+"'; DELETE FROM platform_user WHERE id='"+created['id']+"'")
