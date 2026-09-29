from smoke_support import *
from uuid import uuid4
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
owner=str(uuid4());name='qa_quota_'+uuid4().hex;seed=str(uuid4());created=[];actors=[client(),client()]
def upload(actor,body):return call(actor,'/account/files?filename=quota.txt','POST',body,{'Content-Type':'application/octet-stream',**token(actor)})
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{owner}','{name}','Quota QA',password_hash FROM platform_user WHERE username='admin'")
    for actor in actors:assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    a=actors[0];usage=json.loads(call(a,'/account/files/usage')[1]);assert usage['chargedBytes']==0 and usage['fileCount']==0
    capacity=usage['maximumBytes'];slots=usage['maximumFiles'];assert capacity>1 and 1<slots<=5000
    assert call(client(),'/account/files/usage')[0]==401
    sql(f"INSERT INTO platform_file(id,owner_id,storage_backend,original_name,reserved_bytes) VALUES('{seed}','{owner}','local','Quota fixture',{capacity-1})")
    assert upload(a,b'xx')[0]==409
    gate=Barrier(2)
    def race(actor):gate.wait();return upload(actor,b'x')
    with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(race,actors))
    for status,body in results:
        if status==201:created.append(json.loads(body)['id'])
    assert sorted(status for status,body in results)==[201,409]
    assert json.loads(call(a,'/account/files/usage')[1])['chargedBytes']==capacity
    id=created[-1];assert call(a,'/account/files/'+id+'?version=1','DELETE',headers=token(a))[0]==204;created.remove(id)
    sql(f"UPDATE platform_file SET state='DELETING',reserved_bytes={capacity} WHERE id='{seed}'")
    assert upload(a,b'x')[0]==409
    sql(f"DELETE FROM platform_file WHERE id='{seed}'")
    sql(f"INSERT INTO platform_file(id,owner_id,storage_backend,original_name,reserved_bytes) SELECT gen_random_uuid(),'{owner}','local','Slot fixture',0 FROM generate_series(1,{slots})")
    assert upload(a,b'')[0]==409
    assert json.loads(call(a,'/account/files/usage')[1])['fileCount']==slots
    sql(f"DELETE FROM platform_file WHERE owner_id='{owner}'")
    assert json.loads(call(a,'/account/files/usage')[1])['chargedBytes']==0
    print('PASS: usage isolation, byte reservations, concurrent last-byte admission, pending deletion remains charged, zero-byte file count limit and release')
finally:
    for id in created:
        status,body=call(actors[0],'/account/files/'+id)
        if status==200:call(actors[0],'/account/files/'+id+'?version='+str(json.loads(body)['version']),'DELETE',headers=token(actors[0]))
    sql(f"DELETE FROM platform_file WHERE owner_id='{owner}'; DELETE FROM platform_audit WHERE actor='{name}'; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{owner}'")
