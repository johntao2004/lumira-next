from smoke_support import *
from uuid import uuid4
from hashlib import sha256
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import time
owner=str(uuid4());name='qa_upload_rate_'+uuid4().hex;key='upload-account:'+sha256(name.encode()).hexdigest();actors=[client(),client()];created=[]
def upload(actor):
    request=urllib.request.Request('http://127.0.0.1:8010/api/account/files?filename=rate.txt',data=b'x',headers={'Content-Type':'application/octet-stream',**token(actor)},method='POST')
    try:
        with actor.open(request,timeout=20) as response:return response.status,response.read(),dict(response.headers)
    except urllib.error.HTTPError as response:return response.code,response.read(),dict(response.headers)
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{owner}','{name}','Rate QA',password_hash FROM platform_user WHERE username='admin'")
    for actor in actors:assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    seconds=float(sql("SELECT extract(second from clock_timestamp())"))
    if seconds>50:time.sleep(61-seconds)
    sql(f"INSERT INTO platform_rate_bucket(bucket_key,window_start,attempts) VALUES('{key}',date_trunc('minute',CURRENT_TIMESTAMP),29)")
    gate=Barrier(2)
    def race(actor):gate.wait();return upload(actor)
    with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(race,actors))
    for status,body,headers in results:
        if status==201:created.append(json.loads(body)['id'])
        if status==429:assert {k.lower():v for k,v in headers.items()}['retry-after']=='60' and json.loads(body)['detail']=='uploadRateLimited'
    assert sorted(status for status,body,headers in results)==[201,429]
    assert upload(actors[0])[0]==429
    assert sql(f"SELECT attempts FROM platform_rate_bucket WHERE bucket_key='{key}'")=='31'
    assert sql(f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}'")=='1'
    assert sql(f"SELECT count(*) FROM platform_audit WHERE actor='{name}' AND action='file.upload'")=='1'
    sql(f"UPDATE platform_rate_bucket SET window_start=now()-interval '2 minutes' WHERE bucket_key='{key}'")
    status,body,headers=upload(actors[0]);assert status==201;created.append(json.loads(body)['id'])
    assert sql(f"SELECT attempts FROM platform_rate_bucket WHERE bucket_key='{key}'")=='1'
    print('PASS: concurrent last upload slot, committed saturated denials, Retry-After, no rejected-file metadata/audit, expired window reset')
finally:
    for id in created:call(actors[0],'/account/files/'+id+'?version=1','DELETE',headers=token(actors[0]))
    sql(f"DELETE FROM platform_rate_bucket WHERE bucket_key='{key}'; DELETE FROM platform_audit WHERE actor='{name}'; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{owner}'")
