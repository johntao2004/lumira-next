from smoke_support import *
from uuid import uuid4
from hashlib import sha256
import http.client
owner=str(uuid4());other=str(uuid4());name='qa_file_'+uuid4().hex;second=name+'_other';created=[];allFiles=[]
a=client();b=client()
def upload(actor,filename,body,csrf=True):
    return call(actor,'/account/files?'+urllib.parse.urlencode({'filename':filename}),'POST',body,{'Content-Type':'application/octet-stream',**(token(actor) if csrf else {})})
try:
    for id,username in [(owner,name),(other,second)]:sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{id}','{username}','File QA',password_hash FROM platform_user WHERE username='admin'")
    assert login(a,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200 and login(b,second,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
    assert upload(a,'bad/path',b'x')[0]==400
    assert upload(a,'test',b'x',False)[0]==403
    for filename in ['Alpha report.txt','alpha%_report.txt','Other.txt']:
        status,body=upload(a,filename,b'list fixture');assert status==201
        created.append(json.loads(body)['id']);allFiles.append(created[-1])
    first=json.loads(call(a,'/account/files?pageSize=2')[1]);secondPage=json.loads(call(a,'/account/files?page=2&pageSize=2')[1])
    assert first['total']==3 and len(first['items'])==2 and len(secondPage['items'])==1
    assert len({row['id'] for row in first['items']+secondPage['items']})==3
    assert json.loads(call(a,'/account/files?search=ALPHA')[1])['total']==2
    assert json.loads(call(a,'/account/files?search=%25_')[1])['total']==1
    assert json.loads(call(b,'/account/files')[1])['total']==0
    assert call(client(),'/account/files')[0]==401
    assert call(a,'/account/files?pageSize=101')[0]==400
    assert call(a,'/account/files?page=0')[0]==400
    for id in created[:]:
        assert call(a,'/account/files/'+id+'?version=1','DELETE',headers=token(a))[0]==204
        created.remove(id)
    for body in [b'hello files',b'']:
        status,response=upload(a,'report.txt',body);assert status==201,status
        entry=json.loads(response);id=entry['id'];created.append(id);allFiles.append(id);path='/account/files/'+id
        assert entry['state']=='READY' and entry['sizeBytes']==len(body) and entry['sha256']==sha256(body).hexdigest()
        assert call(a,path)[0]==200
        assert call(client(),path)[0]==401
        assert call(b,path)[0]==404 and call(b,path+'/content')[0]==404
        assert call(b,path+'?version=1','DELETE',headers=token(b))[0]==404
        request=urllib.request.Request('http://127.0.0.1:8010/api'+path+'/content')
        with a.open(request,timeout=20) as response:
            assert response.read()==body
            assert response.headers['Content-Type'].startswith('application/octet-stream')
            assert response.headers['Content-Disposition'].startswith('attachment;')
            assert response.headers['X-Content-Type-Options']=='nosniff'
        assert call(a,path+'?version=0','DELETE',headers=token(a))[0]==409
        assert sql(f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{id}'")== 'file.upload'
        assert call(a,path+'?version=1','DELETE',headers=token(a))[0]==204
        assert call(a,path)[0]==404 and call(a,path+'/content')[0]==404
        created.remove(id)
    # Verify early rejection without racing a server close against a large socket write.
    req=urllib.request.Request('http://127.0.0.1:8090/api/account/files?filename=large.bin')
    for handler in a.handlers:
        if isinstance(handler,urllib.request.HTTPCookieProcessor):handler.cookiejar.add_cookie_header(req)
    connection=http.client.HTTPConnection('127.0.0.1',8090,timeout=20)
    try:
        connection.putrequest('POST','/api/account/files?filename=large.bin')
        for key,value in {**dict(req.header_items()),**token(a),'Content-Type':'application/octet-stream','Content-Length':str(20*1024*1024+1)}.items():connection.putheader(key,value)
        connection.endheaders()
        assert connection.getresponse().status==413
    finally:connection.close()
    assert sql(f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}'")=='0'
    for id in allFiles:
        assert sql(f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{id}'")== 'file.delete,file.deleteRequested,file.upload'
    print('PASS: raw uploads including empty file, exact digest/content, attachment headers, CSRF, anonymous/foreign rejection, stale deletion, deletion and size bound')
finally:
    for id in created:
        status,body=call(a,'/account/files/'+id)
        if status==200:call(a,'/account/files/'+id+'?version='+str(json.loads(body)['version']),'DELETE',headers=token(a))
    sql(f"DELETE FROM platform_audit WHERE actor IN ('{name}','{second}'); DELETE FROM spring_session WHERE principal_name IN ('{name}','{second}'); DELETE FROM platform_user WHERE id IN ('{owner}','{other}')")
