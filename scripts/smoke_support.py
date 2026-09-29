"""Shared local HTTP/SQL helpers; never log secrets or touch the legacy database."""
import json, os, subprocess, urllib.request, urllib.error, urllib.parse, http.cookiejar
from pathlib import Path
config=json.loads((Path(__file__).resolve().parents[1]/'.runtime/local.json').read_text())
def psql_command():
    return [os.environ.get('PSQL_BIN','/opt/homebrew/opt/postgresql@18/bin/psql'),'-X','-A','-t','-h','127.0.0.1','-U',config['LUMIRA_DB_USER'],'-d','lumira_next','-v','ON_ERROR_STOP=1']
def psql_environment(): return {**os.environ,'PGPASSWORD':config['LUMIRA_DB_PASSWORD']}
def sql(statement):
    result=subprocess.run(psql_command(),input=statement,text=True,capture_output=True,env=psql_environment(),timeout=20)
    assert result.returncode==0, result.stderr or 'Database fixture operation failed'
    return result.stdout.strip()
def client():
    # The desktop proxy is enabled globally on macOS; local smoke tests must
    # connect directly so a proxy cannot close the loopback request.
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    )
def call(c,path,method='GET',data=None,headers=None):
    req=urllib.request.Request('http://127.0.0.1:8010/api'+path,data=data,headers=headers or {},method=method)
    try:
        with c.open(req,timeout=20) as r:return r.status,r.read()
    except urllib.error.HTTPError as e:return e.code,e.read()
def token(c):
    status,body=call(c,'/auth/csrf');assert status==200
    value=json.loads(body);return {value['headerName']:value['token']}
def login(c,username,password):
    return call(c,'/auth/login','POST',urllib.parse.urlencode({'username':username,'password':password}).encode(),{**token(c),'Content-Type':'application/x-www-form-urlencoded'})[0]
def write(c,path,method,payload):
    return call(c,path,method,json.dumps(payload).encode(),{**token(c),'Content-Type':'application/json'})
