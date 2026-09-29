"""Owner-isolated draft storage through the running HTTP/security stack."""
import json,uuid
from smoke_support import client,call,login,write,sql,config
names=['qa_draft_'+uuid.uuid4().hex for _ in range(2)];ids=[str(uuid.uuid4()) for _ in names];clients=[client(),client()]
path='/account/drafts/common.form'
try:
 for name,owner,c in zip(names,ids,clients):
  sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Draft QA',password_hash FROM platform_user WHERE username='admin'"%(owner,name))
  assert login(c,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
 a,b=clients
 assert call(client(),'/account/drafts')[0]==401
 assert call(client(),path)[0]==401
 assert call(a,path)[0]==204
 payload={'revision':None,'payload':{'text':'中文','nested':[1,True]}}
 assert call(a,path,'PUT',json.dumps(payload).encode(),{'Content-Type':'application/json'})[0]==403
 status,body=write(a,path,'PUT',payload);assert status==200;first=json.loads(body);assert first['payload']==payload['payload'];assert set(first)=={'revision','payload','updatedAt'}
 assert call(b,path)[0]==204
 status,body=call(a,'/account/drafts');assert status==200;listing=json.loads(body);assert listing['total']==1
 assert set(listing['items'][0])=={'id','draftKey','revision','payloadBytes','updatedAt'}
 assert json.loads(call(b,'/account/drafts')[1])['total']==0
 assert call(a,'/account/drafts?page=0')[0]==400
 assert call(a,'/account/drafts?pageSize=101')[0]==400
 sql("INSERT INTO platform_account_draft(id,owner_id,draft_key,revision,payload,payload_bytes) SELECT gen_random_uuid(),'%s','fixture.'||n,gen_random_uuid(),'{}',2 FROM generate_series(1,25) n"%ids[0])
 first_page=json.loads(call(a,'/account/drafts?page=1&pageSize=20')[1]);second_page=json.loads(call(a,'/account/drafts?page=2&pageSize=20')[1]);assert first_page['total']==26 and len(first_page['items'])==20 and len(second_page['items'])==6
 assert len({row['id'] for row in first_page['items']+second_page['items']})==26
 assert json.loads(call(a,'/account/drafts?search=FIXTURE.1')[1])['total']==11
 status,body=call(a,path);assert status==200 and json.loads(body)==first
 assert write(a,path,'PUT',payload)[0]==409
 assert write(a,path,'PUT',{'revision':first['revision'],'payload':None})[0]==400
 assert write(a,'/account/drafts/UPPER','PUT',payload)[0]==400
 assert write(a,path,'PUT',{'revision':first['revision'],'payload':'x'*1005000})[0]==413
 status,body=write(a,path,'PUT',{'revision':first['revision'],'payload':{'text':'updated'}});assert status==200;latest=json.loads(body)
 assert latest['revision']!=first['revision']
 assert write(a,path+'?revision='+first['revision'],'DELETE',{})[0]==409
 assert write(b,path+'?revision='+latest['revision'],'DELETE',{})[0]==204
 assert call(a,path)[0]==200
 assert write(a,path+'?revision='+latest['revision'],'DELETE',{})[0]==204
 assert call(a,path)[0]==204
 print('PASS: draft persistence, private ownership, CSRF, body/key validation, stale save/delete, paged owner-only metadata and safe fields')
finally:
 for name,owner,c in zip(names,ids,clients):
  try:write(c,'/auth/logout','POST',{})
  except Exception:pass
  sql("DELETE FROM platform_audit WHERE actor='%s'; DELETE FROM platform_user WHERE id='%s'"%(name,owner))
