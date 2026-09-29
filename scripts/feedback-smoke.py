"""Real feedback permission/module/API regression using isolated accounts and records."""
import base64,json,uuid
from smoke_support import client,call,login,token,write,sql,config
names=['qa_feedback_'+uuid.uuid4().hex for _ in range(2)];owners=[str(uuid.uuid4()) for _ in names];role=str(uuid.uuid4());a,b,admin=client(),client(),client();original=None;records=[];image_id=None
def module():
 status,body=call(admin,'/platform/modules');assert status==200
 return next(row for row in json.loads(body) if row['id']=='feedback')
def toggle(enabled):
 state=module();assert write(admin,'/platform/modules/feedback/state','PUT',{'enabled':enabled,'version':state['stateVersion'],'reason':'Feedback HTTP verification'})[0]==200
try:
 assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200;original=module()['enabled']
 for name,owner,c in zip(names,owners,[a,b]):
  sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Feedback QA',password_hash FROM platform_user WHERE username='admin'"%(owner,name))
  assert login(c,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
 assert call(client(),'/feedback')[0]==401
 assert call(a,'/feedback')[0]==403
 for owner in owners:sql("INSERT INTO platform_authority VALUES('%s','platform:feedback:read'),('%s','platform:feedback:create')"%(owner,owner))
 toggle(False);assert call(a,'/feedback')[0]==503;toggle(True)
 status,body=call(a,'/feedback/options');assert status==200;assert [s['value'] for s in json.loads(body)['statuses']]==['OPEN','PROCESSING','RESOLVED','CLOSED']
 sql("DELETE FROM platform_authority WHERE user_id='%s' AND authority='platform:feedback:read'"%owners[0])
 image=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
 status,body=call(a,'/feedback/images?filename=smoke.png','POST',image,{**token(a),'Content-Type':'application/octet-stream'});assert status==201;image_id=json.loads(body)['id']
 payload={'title':'Feedback QA','detailHtml':f"<p>Issue</p><img src='/api/feedback/images/{image_id}' alt='screenshot'><script>alert(1)</script>",'priority':'HIGH'}
 assert write(a,'/feedback','POST',payload)[0]==201
 sql("INSERT INTO platform_authority(user_id,authority) VALUES('%s','platform:feedback:read')"%owners[0])
 assert call(a,'/feedback','POST',json.dumps(payload).encode(),{'Content-Type':'application/json'})[0]==403
 status,body=write(a,'/feedback','POST',payload);assert status==201;first=json.loads(body);records.append(first['id']);assert '<script' not in first['detailHtml'] and f'/api/feedback/images/{image_id}' in first['detailHtml'] and first['ownerId']==owners[0]
 status,body=call(a,'/account/notifications');assert status==200 and json.loads(body)['total']==2 and all(item['kind']=='feedback' for item in json.loads(body)['items'])
 assert call(a,f'/feedback/images/{image_id}')[0]==200
 assert call(admin,f'/feedback/images/{image_id}')[0]==200
 assert call(a,f'/account/files/{image_id}?version=1','DELETE',None,token(a))[0]==409
 payload_b={'title':'Feedback QA','detailHtml':'<p>Issue</p>','priority':'HIGH'}
 status,body=write(b,'/feedback','POST',payload_b);assert status==201;second=json.loads(body);records.append(second['id'])
 assert call(b,'/feedback/'+first['id'])[0]==404
 # Management scope is exposed through the dedicated route; the user route
 # must remain owner-scoped even when an arbitrary query parameter is sent.
 assert json.loads(call(b,'/feedback?scope=admin')[1])['total']==1
 row=json.loads(call(a,'/feedback')[1])['items'][0];assert 'detailHtml' not in row and 'adminReply' not in row
 assert call(a,'/feedback/manage')[0]==403
 sql("INSERT INTO platform_role(id,code,name,enabled,data_scope) VALUES('%s','%s','Feedback QA',true,'SELF'); INSERT INTO platform_role_authority VALUES('%s','platform:feedback:manage'); INSERT INTO platform_user_role VALUES('%s','%s')"%(role,names[0],role,owners[0],role))
 target='/feedback/manage/'+second['id'];change={'version':1,'status':'PROCESSING','adminReply':'Investigating'}
 assert call(a,'/feedback/manage')[0]==403 and write(a,target,'PUT',change)[0]==403
 sql("UPDATE platform_role SET data_scope='ALL' WHERE id='%s'"%role)
 assert call(a,'/feedback/manage')[0]==200
 status,body=write(a,target,'PUT',change);assert status==200;assert json.loads(body)['version']==2
 assert write(a,target,'PUT',change)[0]==409
 assert write(a,target,'PUT',{'version':2,'status':'UNKNOWN'})[0]==400
 status,body=write(a,target,'PUT',{'version':2,'status':'RESOLVED','adminReply':'Fixed'});assert status==200;assert json.loads(body)['handledAt'] is not None
 assert json.loads(call(b,'/feedback/'+second['id'])[1])['adminReply']=='Fixed'
 assert call(a,'/feedback?page=0')[0]==400
 assert write(a,'/feedback','POST',{**payload,'detailHtml':'x'*811000})[0]==413
 sql("UPDATE platform_role SET enabled=false WHERE id='%s'"%role);assert call(a,'/feedback/manage')[0]==403
 sql("DELETE FROM platform_authority WHERE user_id='%s' AND authority='platform:feedback:create'"%owners[0]);assert write(a,'/feedback','POST',payload)[0]==403
 toggle(False);assert call(b,'/feedback/'+second['id'])[0]==503
 print('PASS: feedback module enforcement, normalized image upload/reference authorization, deletion protection, independent grants/global scope, owner isolation, sanitization, processing/version conflicts, CSRF/body bounds and live revocation')
finally:
 try:
  if original is not None:toggle(original)
 finally:
  if image_id:
   sql("DELETE FROM platform_feedback WHERE owner_id IN ('%s','%s')"%(owners[0],owners[1]))
   try:call(a,f'/account/files/{image_id}?version=1','DELETE',None,token(a))
   except Exception:pass
  for c in [a,b,admin]:
   try:write(c,'/auth/logout','POST',{})
   except Exception:pass
  for name,owner in zip(names,owners):sql("DELETE FROM platform_audit WHERE actor='%s'; DELETE FROM platform_feedback WHERE owner_id='%s'; DELETE FROM platform_user_role WHERE user_id='%s'; DELETE FROM platform_user WHERE id='%s'"%(name,owner,owner,owner))
  sql("DELETE FROM platform_role WHERE id='%s'"%role)
