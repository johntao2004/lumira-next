"""Observe a real blocked write, revoke its permission, then verify rejection and no mutation."""
from smoke_support import *
import uuid, time
from concurrent.futures import ThreadPoolExecutor

prefix='qa_concurrency_'+uuid.uuid4().hex
actor,target=str(uuid.uuid4()),str(uuid.uuid4())
locker=None
pool=ThreadPoolExecutor(max_workers=2)
try:
    for id,name in [(actor,prefix),(target,prefix+'_target')]:
        sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '"+id+"','"+name+"','Unchanged',password_hash FROM platform_user WHERE username='admin'")
    sql("INSERT INTO platform_authority(user_id,authority) VALUES('"+actor+"','platform:users:write')")
    c=client();assert login(c,prefix,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    headers={**token(c),'Content-Type':'application/json'}
    locker=subprocess.Popen(psql_command(),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1,env=psql_environment())
    locker.stdin.write("BEGIN;\nSELECT pg_advisory_xact_lock(481026);\n\\echo LOCK_READY\n");locker.stdin.flush()
    # A dedicated reader avoids relying on fixed sleeps or buffered readiness assumptions.
    def ready():
        for line in locker.stdout:
            if line.strip()=='LOCK_READY':return True
        return False
    assert pool.submit(ready).result(timeout=10),'Lock acquisition failed'
    pending=pool.submit(call,c,'/platform/users/'+target,'PUT',json.dumps({'displayName':'Must not be saved','enabled':True,'version':0}).encode(),headers)
    deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        waiting=sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=481026 AND NOT granted")
        if int(waiting)>0:break
        assert not pending.done(),'Request completed before reaching administration lock'
        time.sleep(0.05)
    else:raise AssertionError('No blocked administration request observed')
    locker.stdin.write("DELETE FROM platform_authority WHERE user_id='"+actor+"' AND authority='platform:users:write';\nCOMMIT;\n\\q\n");locker.stdin.flush()
    assert pending.result(timeout=15)[0]==403,'Permission revoked during wait was not enforced'
    locker.wait(timeout=10);assert locker.returncode==0
    assert sql("SELECT display_name||':'||version FROM platform_user WHERE id='"+target+"'")=='Unchanged:0'
    assert sql("SELECT count(*) FROM platform_audit WHERE target_id='"+target+"'")=='0'
    # Two simultaneous requests holding the same version must not both succeed.
    sql("INSERT INTO platform_authority(user_id,authority) VALUES('"+actor+"','platform:users:write')")
    clients=[client(),client()]
    for item in clients:assert login(item,prefix,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    tokens=[{**token(item),'Content-Type':'application/json'} for item in clients]
    futures=[pool.submit(call,item,'/platform/users/'+target,'PUT',json.dumps({'displayName':'Concurrent '+str(i),'enabled':True,'version':0}).encode(),tokens[i]) for i,item in enumerate(clients)]
    assert sorted(f.result(timeout=15)[0] for f in futures)==[200,409]
    assert sql("SELECT version FROM platform_user WHERE id='"+target+"'")=='1'
    assert sql("SELECT count(*) FROM platform_audit WHERE target_id='"+target+"'")=='1'
    print('PASS: observed lock wait, permission revocation rejects queued write, no write/audit on denial, competing versions produce one success and one conflict')
finally:
    if locker and locker.poll() is None:
        locker.terminate()
        try:locker.wait(timeout=5)
        except subprocess.TimeoutExpired:locker.kill();locker.wait(timeout=5)
    pool.shutdown(wait=True,cancel_futures=True)
    for id,name in [(actor,prefix),(target,prefix+'_target')]:
        sql("DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM spring_session WHERE principal_name='"+name+"'; DELETE FROM platform_user WHERE id='"+id+"'")
