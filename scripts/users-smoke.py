"""Verify user administration using an isolated account and cleanup."""
import json, os, subprocess, uuid, urllib.request, urllib.error, urllib.parse, http.cookiejar, secrets
from pathlib import Path
config = json.loads((Path(__file__).resolve().parents[1] / '.runtime/local.json').read_text())
username = 'qa_password_' + uuid.uuid4().hex
password = config['LUMIRA_BOOTSTRAP_PASSWORD']
new_password = secrets.token_urlsafe(24)
def sql(statement):
    result = subprocess.run([os.environ.get('PSQL_BIN', '/opt/homebrew/opt/postgresql@18/bin/psql'), '-X', '-h', '127.0.0.1', '-U', config['LUMIRA_DB_USER'], '-d', 'lumira_next', '-v', 'ON_ERROR_STOP=1'], input=statement, text=True, capture_output=True, env={**os.environ, 'PGPASSWORD':config['LUMIRA_DB_PASSWORD']})
    assert result.returncode == 0, 'Database fixture operation failed'
def client(): return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(c, path, data=None, headers=None):
    req = urllib.request.Request('http://127.0.0.1:8010/api'+path, data=data, headers=headers or {})
    try:
        with c.open(req, timeout=20) as res: return res.status, res.read()
    except urllib.error.HTTPError as error: return error.code, error.read()
def token(c):
    status,body=call(c,'/auth/csrf'); assert status==200
    t=json.loads(body); return {t['headerName']:t['token']}
def login(c, secret): return call(c,'/auth/login',urllib.parse.urlencode({'username':username,'password':secret}).encode(),{**token(c),'Content-Type':'application/x-www-form-urlencoded'})[0]
def change(c, old, new): return call(c,'/account/password',json.dumps({'currentPassword':old,'newPassword':new}).encode(),{**token(c),'Content-Type':'application/json'})[0]
test_username=username
username='admin'
admin=client()
assert login(admin,password)==200
def write(path,method,payload,c=admin):
    req=urllib.request.Request('http://127.0.0.1:8010/api'+path,data=json.dumps(payload).encode(),method=method,headers={**token(c),'Content-Type':'application/json'})
    try:
        with c.open(req,timeout=20) as res:return res.status,res.read()
    except urllib.error.HTTPError as error:return error.code,error.read()
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
