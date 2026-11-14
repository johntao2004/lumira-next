"""验证外部构建、真实权限及升级恢复；只创建独立 QA 数据库。
Verify external builds, real authorization and upgrade recovery using newly created QA databases only.
"""
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / '.runtime/independent-app-verification.json'
REPORT.parent.mkdir(parents=True, exist_ok=True)
assert os.environ.get('PGHOST') in {'127.0.0.1', 'localhost'}, 'Use a local disposable PostgreSQL instance'
assert os.environ.get('PGPASSWORD'), 'Supply QA credentials through the environment'
results = []
def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)
def check(name, condition):
    if not condition:
        raise AssertionError(name)
    results.append(name)
def sql(database, statement):
    return subprocess.check_output(['psql','-X','-At','-v','ON_ERROR_STOP=1','-d',database,'-c',statement], text=True).strip()

with tempfile.TemporaryDirectory(prefix='lumira-independent-', dir='/tmp') as directory:
    work = Path(directory)
    app = work / 'notes'
    shutil.copytree(ROOT/'examples/independent-notes', app, ignore=shutil.ignore_patterns('target'))
    # 复制到仓库之外，POM 没有相对父工程或宿主依赖。 Copy outside the repository; the POM has no relative project parent or host dependency.
    maven = ROOT/'backend/mvnw'
    build_log = ROOT/'.runtime/independent-app-build.log'
    with build_log.open('w') as output:
        run([str(maven),'-f',str(app/'pom.xml'),'-B','verify','dependency:build-classpath','-Dmdep.outputFile=target/classpath.txt'], stdout=output, stderr=subprocess.STDOUT)
    cp = (app/'target/classpath.txt').read_text()
    check('external Maven build and service authorization tests', True)
    check('standalone classpath excludes host implementation', 'platform-server' not in cp and '/backend/target/' not in cp)
    private = work/'PrivateImport.java'
    private.write_text('import com.lumira.platform.identity.AdministrationGuard; class PrivateImport { AdministrationGuard guard; }')
    denied = subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javac'),'-cp',cp,str(private)],capture_output=True,text=True)
    check('private host import fails real javac', denied.returncode != 0 and 'AdministrationGuard' in denied.stderr)
    suffix = secrets.token_hex(6)
    databases = []
    process = None
    reader = secrets.token_urlsafe(24)
    editor = secrets.token_urlsafe(24)
    port = int(os.environ.get('LUMIRA_INDEPENDENT_PORT','18096'))
    base = f'http://127.0.0.1:{port}'
    def client(): return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(opener, user, password, method='GET', body=None, csrf=None, path='/notes'):
        headers = {}
        if user: headers['Authorization']='Basic '+base64.b64encode(f'{user}:{password}'.encode()).decode()
        if body is not None: headers['Content-Type']='application/json'
        if csrf: headers[csrf['header']]=csrf['token']
        req=urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with opener.open(req, timeout=4) as response: return response.status, json.loads(response.read() or b'null')
        except urllib.error.HTTPError as error: return error.code, error.read().decode()
    def stop():
        global process
        if process:
            process.terminate()
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            process=None
    def start(database, target=None):
        global process
        env={**os.environ,'NOTES_DB_URL':f'jdbc:postgresql://{os.environ["PGHOST"]}:{os.environ.get("PGPORT","5432")}/{database}', 'NOTES_DB_USER':os.environ['PGUSER'],'NOTES_DB_PASSWORD':os.environ['PGPASSWORD'],'NOTES_READER_PASSWORD':reader,'NOTES_EDITOR_PASSWORD':editor,'NOTES_PORT':str(port)}
        args=[str(Path(os.environ['JAVA_HOME'])/'bin/java'),'-jar',str(app/'target/independent-notes-0.1.0-alpha.1.jar')]
        if target: args.append('--spring.flyway.target='+target)
        with (ROOT/'.runtime/independent-app-server.log').open('a') as output:
            process=subprocess.Popen(args,env=env,stdout=output,stderr=subprocess.STDOUT)
        for _ in range(100):
            if process.poll() is not None: raise RuntimeError('Independent app failed startup; inspect local server log')
            try:
                if request(client(),'reader',reader)[0]==200:return
            except (OSError,urllib.error.URLError): pass
            time.sleep(.2)
        raise RuntimeError('Independent app startup timeout')
    try:
        db='lumira_sdk_qa_'+suffix
        run(['createdb',db]);databases.append(db)
        start(db,'1')
        check('V1 starts on a new database', sql(db,"SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success")=='1')
        check('anonymous read denied', request(client(),None,None)[0]==401)
        rc=client(); ec=client()
        check('reader list allowed', request(rc,'reader',reader)[0]==200)
        rtoken=request(rc,'reader',reader,path='/csrf')[1]
        etoken=request(ec,'editor',editor,path='/csrf')[1]
        check('reader write denied with valid CSRF', request(rc,'reader',reader,'POST',{'text':'blocked'},rtoken)[0]==403)
        csrf_status=request(ec,'editor',editor,'POST',{'text':'blocked'})[0]
        check('editor write without CSRF denied', csrf_status in (401,403))
        status,created=request(ec,'editor',editor,'POST',{'text':'retained note'},etoken)
        check('editor write allowed',status==200)
        check('business row and audit persisted atomically',sql(db,"SELECT (SELECT count(*) FROM notes_entry)||':'||(SELECT count(*) FROM notes_audit)")=='1:1')
        sql(db,"CREATE FUNCTION notes_qa_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'qa audit failure'; END $$; CREATE TRIGGER notes_qa_audit_failure BEFORE INSERT ON notes_audit FOR EACH ROW EXECUTE FUNCTION notes_qa_reject_audit();")
        check('audit failure rejects business write',request(ec,'editor',editor,'POST',{'text':'must rollback'},etoken)[0]==500)
        check('audit failure rolls back business row',sql(db,"SELECT count(*) FROM notes_entry")=='1')
        sql(db,"DROP TRIGGER notes_qa_audit_failure ON notes_audit; DROP FUNCTION notes_qa_reject_audit();")
        stop(); start(db)
        check('V1 to V2 migration applied',sql(db,"SELECT count(*) FROM flyway_schema_history WHERE version='2' AND success")=='1')
        check('upgrade retains record and new default',sql(db,"SELECT text||':'||tag FROM notes_entry")=='retained note:')
        stop()
        dump=work/'backup.dump';run(['pg_dump','-Fc','-f',str(dump),db])
        restored='lumira_sdk_restore_qa_'+suffix;run(['createdb',restored]);databases.append(restored)
        run(['pg_restore','--exit-on-error','--no-owner','-d',restored,str(dump)])
        start(restored)
        status,records=request(client(),'reader',reader)
        check('restored application reads original record',status==200 and len(records)==1 and records[0]['id']==created['id'])
        check('restore retains audit and migration history',sql(restored,"SELECT (SELECT count(*) FROM notes_audit)||':'||(SELECT count(*) FROM flyway_schema_history WHERE success)")=='1:2')
        rc=client();rtoken=request(rc,'reader',reader,path='/csrf')[1]
        check('read-only boundary survives restore',request(rc,'reader',reader,'POST',{'text':'blocked'},rtoken)[0]==403)
    finally:
        stop()
        # 仅清理由本次运行成功创建的随机数据库。 Drop only random databases successfully created by this run.
        for db in databases: run(['dropdb',db])
REPORT.write_text(json.dumps({'passed':len(results),'checks':results,'scope':'local isolated example; not host migration or production capacity certification'},indent=2)+'\n')
print(f'PASS: {len(results)} independent build, authorization, upgrade and restore checks')
