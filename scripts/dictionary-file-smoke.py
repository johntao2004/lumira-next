"""Legacy upload preview/hash confirmation with real authorization and transaction checks."""
from smoke_support import *
import uuid, hashlib
base='/platform/dictionaries'; code='qa_file_'+uuid.uuid4().hex; created=None
admin=client(); assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
body=b'itemValue\titemLabel\tparentItemValue\tlevelNo\tleaf\nroot\tRoot\t\t1\tfalse\nchild\tChild\troot\t2\ttrue\n'
params={'filename':'tree.txt','code':code,'name':'File QA','enabled':'true','structure':'TREE','remark':'file test'}
def upload(client,action,content=body,extra=None,csrf=True):
    query=urllib.parse.urlencode({**params,**(extra or {})})
    return call(client,base+'/'+action+'?'+query,'POST',content,{'Content-Type':'application/octet-stream',**(token(client) if csrf else {})})
try:
    status,result=upload(admin,'file-preview'); assert status==200,(status,result)
    preview=json.loads(result); assert preview['summary']['itemCount']==2 and len(preview['rows'])==2
    digest=hashlib.sha256(body).hexdigest(); assert preview['sha256']==digest
    assert sql("SELECT count(*) FROM platform_dictionary WHERE code='"+code+"'")=='0'
    assert upload(admin,'file-import',extra={'expectedSha256':'0'*64})[0]==409
    assert upload(admin,'file-import',body+b'\n',{'expectedSha256':digest})[0]==409
    assert upload(admin,'file-import',extra={'expectedSha256':digest},csrf=False)[0]==403
    assert upload(client(),'file-preview')[0] in (401,403)
    status,result=upload(admin,'file-import',extra={'expectedSha256':digest}); assert status==201,(status,result)
    created=json.loads(result)['id']
    status,result=call(admin,base+'/'+created+'/export'); assert status==200
    entries=json.loads(result)['items']; assert len(entries)==2 and any(x['parentValue']=='root' for x in entries)
    assert upload(admin,'file-import',extra={'expectedSha256':digest})[0]==409
    assert upload(admin,'file-preview',b'broken')[0]==400
    status,result=upload(admin,'file-preview',b'itemValue\titemLabel\n\nx\t\ny\t\n')
    assert status==400 and [x['row'] for x in json.loads(result)['issues']]==[3,4],(status,result)
    print('PASS: file preview without writes, hash mismatch, CSRF, anonymous denial, import hierarchy and duplicate conflict')
finally:
    if created:
        sql("DELETE FROM platform_dictionary_item WHERE dictionary_id='"+created+"' AND parent_id IS NOT NULL; DELETE FROM platform_dictionary_item WHERE dictionary_id='"+created+"'; DELETE FROM platform_audit WHERE target_id='"+created+"'; DELETE FROM platform_dictionary WHERE id='"+created+"'")
