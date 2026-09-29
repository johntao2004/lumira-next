"""Local, real PostgreSQL-backed authentication smoke. Never prints credentials."""
import json, urllib.request, urllib.error, urllib.parse, http.cookiejar, subprocess, os
from pathlib import Path
config=json.loads((Path(__file__).resolve().parents[1]/'.runtime/local.json').read_text())
base='http://127.0.0.1:8010/api'
jar=http.cookiejar.CookieJar()
# The desktop proxy is enabled globally on macOS; local smoke tests must
# bypass it so loopback requests reach the scaffold directly.
client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(jar))
def call(path,method='GET',data=None,headers=None):
 request=urllib.request.Request(base+path,data=data,headers=headers or {},method=method)
 try:
  with client.open(request,timeout=20) as response:return response.status,response.read()
 except urllib.error.HTTPError as error:return error.code,error.read()
def token():
 status,body=call('/auth/csrf');assert status==200,(status,body);return json.loads(body)
assert call('/auth/me')[0]==401
assert call('/platform/modules')[0]==401
assert call('/auth/login','POST',b'username=admin&password=invalid')[0]==403
csrf=token();headers={csrf['headerName']:csrf['token'],'Content-Type':'application/x-www-form-urlencoded'}
assert call('/auth/login','POST',b'username=admin&password=invalid',headers)[0]==401
status,body=call('/auth/login','POST',urllib.parse.urlencode({'username':'admin','password':config['LUMIRA_BOOTSTRAP_PASSWORD']}).encode(),headers)
assert status==200,(status,body)
status,body=call('/auth/me');assert status==200 and json.loads(body)['username']=='admin'
status,body=call('/platform/modules');assert status==200
module_ids={m['id'] for m in json.loads(body)}
assert {'identity','platform','communication','directory','dictionaries','files','settings','events','navigation','notifications','monitoring','audit-console','feedback','sensitive-words','localization'} <= module_ids
status,body=call('/platform/users');assert status==200 and all('password' not in k for row in json.loads(body)['items'] for k in row)
# Mutate only the independent scaffold bootstrap account, restore even after failures.
psql=os.environ.get('PSQL_BIN','/opt/homebrew/opt/postgresql@18/bin/psql')
def sql(statement):
 result=subprocess.run([psql,'-X','-h','127.0.0.1','-U',config['LUMIRA_DB_USER'],'-d','lumira_next','-v','ON_ERROR_STOP=1'],input=statement,text=True,capture_output=True,env={**os.environ,'PGPASSWORD':config['LUMIRA_DB_PASSWORD']})
 assert result.returncode==0,'Scaffold database check failed'
try:
 sql("DELETE FROM platform_authority WHERE user_id=(SELECT id FROM platform_user WHERE username='admin') AND authority='platform:users:read'")
 assert call('/platform/users')[0]==403,'Revoked permission still allowed'
finally:
 sql("INSERT INTO platform_authority(user_id,authority) SELECT id,'platform:users:read' FROM platform_user WHERE username='admin' ON CONFLICT DO NOTHING")
assert call('/platform/users')[0]==200
# Isolated, non-login fixtures prove pagination beyond the former 100-row cap.
import uuid
prefix = 'qa_' + uuid.uuid4().hex + '_'
try:
 sql("INSERT INTO platform_user(id,username,display_name,password_hash,enabled) SELECT gen_random_uuid(), '"+prefix+"' || lpad(n::text,3,'0'), 'Pagination QA', 'disabled-test-fixture', false FROM generate_series(1,105) n")
 status,body=call('/platform/users?'+urllib.parse.urlencode({'page':1,'pageSize':100,'search':prefix}))
 first=json.loads(body);assert status==200 and first['total']==105 and len(first['items'])==100
 status,body=call('/platform/users?'+urllib.parse.urlencode({'page':2,'pageSize':100,'search':prefix}))
 second=json.loads(body);assert status==200 and second['total']==105 and len(second['items'])==5
 assert not ({row['id'] for row in first['items']} & {row['id'] for row in second['items']})
 assert call('/platform/users?page=0')[0]==400
 assert call('/platform/users?pageSize=101')[0]==400
 status,body=call('/platform/users?search=%25');assert status==200 and json.loads(body)['total']==0, 'Percent must be literal, not a wildcard'
finally:
 sql("DELETE FROM platform_user WHERE left(username,"+str(len(prefix))+")='"+prefix+"'")
print('PASS: 105-user pagination, nonoverlap, search, bounded parameters, fixture cleanup')
old_cookie='; '.join(f'{c.name}={c.value}' for c in jar)
csrf=token();assert call('/auth/logout','POST',b'',{csrf['headerName']:csrf['token']})[0]==204
assert call('/auth/me',headers={'Cookie':old_cookie})[0]==401
print('PASS: anonymous denial, CSRF, invalid login, login, current user, module registry, safe user fields, permission revocation/restoration, logout cookie replay rejection')
