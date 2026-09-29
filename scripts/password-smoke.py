"""Exercise password changes on an isolated temporary account, never on admin."""
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
try:
    sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'"+username+"','Password QA',password_hash FROM platform_user WHERE username='admin'")
    first,second=client(),client()
    assert login(first,password)==200 and login(second,password)==200
    assert change(first,'incorrect-current-password',new_password)==400
    assert change(first,'x'*73,new_password)==400
    assert change(first,'密'*25,new_password)==400
    assert change(first,password,'short')==400
    assert change(first,password,password)==400
    assert change(first,password,new_password)==204
    assert call(first,'/auth/me')[0]==401
    assert call(second,'/auth/me')[0]==401
    assert login(client(),password)==401
    assert login(client(),new_password)==200
    print('PASS: wrong current password, policy, reuse rejection, password change, both sessions revoked, old password rejected, new login accepted')
finally:
    sql("DELETE FROM spring_session WHERE principal_name='"+username+"'; DELETE FROM platform_user WHERE username='"+username+"'")
