from smoke_support import *
from uuid import uuid4
from pathlib import Path
import http.client,time,sys
owner=str(uuid4());name='qa_fence_'+uuid4().hex;actor=client();connection=None;id=None
try:
    sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{owner}','{name}','Fence QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(actor,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    req=urllib.request.Request('http://127.0.0.1:8090/api/account/files?filename=fence.txt')
    for handler in actor.handlers:
        if isinstance(handler,urllib.request.HTTPCookieProcessor):handler.cookiejar.add_cookie_header(req)
    connection=http.client.HTTPConnection('127.0.0.1',8090,timeout=20);connection.putrequest('POST','/api/account/files?filename=fence.txt')
    for key,value in {**dict(req.header_items()),**token(actor),'Content-Type':'application/octet-stream','Content-Length':'2'}.items():connection.putheader(key,value)
    connection.endheaders();connection.send(b'a')
    deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        id=sql(f"SELECT id FROM platform_file WHERE owner_id='{owner}' AND state='UPLOADING'")
        if id:break
        time.sleep(.1)
    assert id,'Upload reservation not observed'
    if "--expired" in sys.argv:sql(f"UPDATE platform_file SET upload_expires_at=now()-interval '1 second' WHERE id='{id}'")
    else:sql(f"UPDATE platform_file SET state='DELETING',version=version+1 WHERE id='{id}'")
    connection.send(b'b');assert connection.getresponse().status==409
    root=Path(config.get('LUMIRA_FILES_LOCAL_ROOT','.runtime/files'));assert not (root/id[:2]/(id+'.blob')).exists();assert not (root/id[:2]/(id+'.upload')).exists()
    assert sql(f"SELECT count(*) FROM platform_audit WHERE target_id='{id}' AND action='file.upload'")=='0'
    print('PASS: upload paused after reservation, metadata fenced or deadline expired while streaming, late publication rejected and temporary bytes removed without upload-success audit')
finally:
    if connection:connection.close()
    if id:
        status,body=call(actor,'/account/files/'+id)
        if status==200:call(actor,'/account/files/'+id+'?version='+str(json.loads(body)['version']),'DELETE',headers=token(actor))
    sql(f"DELETE FROM platform_audit WHERE actor='{name}'; DELETE FROM spring_session WHERE principal_name='{name}'; DELETE FROM platform_user WHERE id='{owner}'")
