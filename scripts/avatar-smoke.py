from smoke_support import *
from uuid import uuid4
from pathlib import Path
import struct,zlib
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

owner=str(uuid4());other=str(uuid4());name='qa_avatar_'+uuid4().hex;second=name+'_other'
a=client();b=client();c=client()
def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,1,8,6,0,0,0))+chunk(b'tEXt',b'Comment\0private avatar metadata')+chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00\xff\x00\xff\x00\xff'))+chunk(b'IEND',b'')
def upload(actor,version,body=png,csrf=True):
    return call(actor,'/account/avatar?version='+str(version),'POST',body,{'Content-Type':'application/octet-stream',**(token(actor) if csrf else {})})
def avatar(actor):return json.loads(call(actor,'/account/avatar')[1])
try:
    for id,username in [(owner,name),(other,second)]:sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{id}','{username}','Avatar QA',password_hash FROM platform_user WHERE username='admin'")
    for actor,username in [(a,name),(b,second),(c,name)]:assert login(actor,username,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert avatar(a)=={'fileId':None,'version':0}
    assert call(client(),'/account/avatar')[0]==401
    assert upload(a,0,csrf=False)[0]==403
    assert upload(a,0,b'<svg/>')[0]==400
    assert upload(a,-1)[0]==400
    assert sql(f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}'")=='0'
    status,body=upload(a,0);assert status==200,(status,body)
    first=json.loads(body);assert first['version']==1 and first['fileId']
    req=urllib.request.Request('http://127.0.0.1:8010/api/account/avatar/content?version=1')
    with a.open(req,timeout=20) as response:
        normalized=response.read();assert normalized.startswith(b'\x89PNG') and b'private avatar metadata' not in normalized
        assert response.headers['Content-Type'].startswith('image/png')
        assert response.headers['Cache-Control']=='no-store' and response.headers['X-Content-Type-Options']=='nosniff'
    assert call(b,'/account/avatar/content?version=1')[0]==404
    assert call(client(),'/account/avatar/content?version=1')[0]==401
    assert call(a,'/account/avatar/content?version=0')[0]==404
    assert call(a,'/account/files/'+first['fileId']+'?version=1','DELETE',headers=token(a))[0]==409
    assert upload(a,0)[0]==409
    # A failed binding audit must roll back both the new image and old-image retirement.
    fault='qa_avatar_fault_'+uuid4().hex
    sql(f"CREATE FUNCTION {fault}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.actor='{name}' AND NEW.action='account.avatarUpdated' THEN RAISE EXCEPTION 'avatar fixture audit failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER {fault} BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION {fault}()")
    try:
        assert upload(a,1)[0]==500
        assert avatar(a)==first
        assert sql(f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}'")=='1'
        assert sql(f"SELECT state FROM platform_file WHERE id='{first['fileId']}'")=='READY'
    finally:
        sql(f"DROP TRIGGER IF EXISTS {fault} ON platform_audit; DROP FUNCTION IF EXISTS {fault}()")
    barrier=Barrier(2)
    def replace(actor):barrier.wait();return upload(actor,1)[0]
    with ThreadPoolExecutor(2) as pool:results=list(pool.map(replace,[a,c]))
    assert sorted(results)==[200,409],results
    current=avatar(a);assert current['version']==2 and current['fileId']!=first['fileId']
    assert sql(f"SELECT state FROM platform_file WHERE id='{first['fileId']}'")=='DELETING'
    assert sql(f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}' AND state='READY'")=='1'
    assert sql(f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}' AND state='UPLOADING'")=='0'
    assert call(a,'/account/avatar?version=1','DELETE',headers=token(a))[0]==409
    status,body=call(a,'/account/avatar?version=2','DELETE',headers=token(a));assert status==200
    assert json.loads(body)=={'fileId':None,'version':3} and avatar(a)['version']==3
    assert call(a,'/account/avatar/content?version=2')[0]==404
    assert upload(a,0)[0]==409  # Removing does not reset the version (ABA protection).
    assert sql(f"SELECT count(*) FROM platform_audit WHERE actor='{name}' AND action='account.avatarUpdated'")=='2'
    assert sql(f"SELECT count(*) FROM platform_audit WHERE actor='{name}' AND action='account.avatarRemoved'")=='1'
    print('PASS: avatar upload/normalized private PNG, metadata stripping, CSRF/anonymous/foreign denial, active-file protection, concurrent replacement 200/409, retired-file state, removal and monotonic versions')
finally:
    # Remove only this fixture's media and bytes, including failed-upload reservations.
    ids=sql(f"SELECT id FROM platform_file WHERE owner_id IN ('{owner}','{other}')").splitlines()
    sql(f"DELETE FROM platform_account_media WHERE user_id IN ('{owner}','{other}')")
    for id in ids:
        uuid=__import__('uuid').UUID(id)
        for suffix in ['.blob','.upload']:(Path('.runtime/files')/str(uuid)[:2]/(str(uuid)+suffix)).unlink(missing_ok=True)
    sql(f"DELETE FROM platform_file WHERE owner_id IN ('{owner}','{other}'); DELETE FROM platform_audit WHERE actor IN ('{name}','{second}'); DELETE FROM spring_session WHERE principal_name IN ('{name}','{second}'); DELETE FROM platform_user WHERE id IN ('{owner}','{other}')")
