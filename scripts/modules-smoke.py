"""Module administration authorization and core-state contract; independent fixtures only."""
import json,uuid
from smoke_support import client,call,login,write,sql,config
name='qa_module_'+uuid.uuid4().hex;owner=str(uuid.uuid4());role=str(uuid.uuid4());c=client()
try:
 sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Module QA',password_hash FROM platform_user WHERE username='admin'"%(owner,name))
 assert call(client(),'/platform/modules')[0]==401
 assert login(c,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
 assert call(c,'/platform/modules')[0]==403
 sql("INSERT INTO platform_authority VALUES('%s','platform:modules:read')"%owner)
 status,body=call(c,'/platform/modules');assert status==200;modules=json.loads(body)
 core=next(m for m in modules if m['id']=='identity');assert core['required'] and core['enabled'] and isinstance(core['version'],str) and isinstance(core['stateVersion'],int)
 history='/platform/modules/identity/history'
 status,body=call(c,history);assert status==200 and json.loads(body)['total']==0
 assert call(c,'/platform/modules/not-registered/history')[0]==404
 assert call(c,history+'?page=0')[0]==400
 assert call(c,history+'?pageSize=101')[0]==400
 path='/platform/modules/identity/state';payload={'enabled':True,'version':core['stateVersion'],'reason':'Module QA'}
 assert write(c,path,'PUT',payload)[0]==403
 sql("INSERT INTO platform_role(id,code,name,enabled,data_scope) VALUES('%s','%s','Module QA',true,'SELF'); INSERT INTO platform_role_authority VALUES('%s','platform:modules:write'); INSERT INTO platform_user_role VALUES('%s','%s')"%(role,name,role,owner,role))
 sql("DELETE FROM platform_authority WHERE user_id='%s' AND authority='platform:modules:read'; INSERT INTO platform_role_authority VALUES('%s','platform:modules:read')"%(owner,role))
 assert call(c,'/platform/modules')[0]==200
 assert call(c,history)[0]==403,'SELF must not read global module history'
 assert write(c,path,'PUT',payload)[0]==403,'SELF must not control global modules' 
 sql("UPDATE platform_role SET data_scope='ALL' WHERE id='%s'"%role)
 status,body=call(c,'/platform/modules/audit-console/history');assert status==200;revisions=json.loads(body)['items'];assert revisions
 assert all(set(row)=={'id','version','enabled','changedBy','changeReason','createdAt'} for row in revisions)
 assert [row['version'] for row in revisions]==sorted([row['version'] for row in revisions],reverse=True)
 assert call(c,path,'PUT',json.dumps(payload).encode(),{'Content-Type':'application/json'})[0]==403
 status,body=write(c,path,'PUT',payload);assert status==200;state=json.loads(body);assert state=={'moduleId':'identity','enabled':True,'version':core['stateVersion']}
 assert write(c,path,'PUT',{**payload,'enabled':False})[0]==409
 assert write(c,path,'PUT',{**payload,'version':core['stateVersion']+1})[0]==409
 assert write(c,'/platform/modules/not-registered/state','PUT',payload)[0]==404
 for invalid in ({'enabled':True},{**payload,'enabled':None},{**payload,'reason':' '},{**payload,'version':-1}):
  status,body=write(c,path,'PUT',invalid);assert status==400,(status,body.decode())
 assert write(c,path,'PUT',{**payload,'reason':'x'*5000})[0]==413
 assert sql("SELECT count(*) FROM platform_audit WHERE actor='%s' AND action IN ('module.enabled','module.disabled')"%name)=='0','No-op or denied requests wrote audit'
 sql("UPDATE platform_role SET enabled=false WHERE id='%s'"%role)
 assert write(c,path,'PUT',payload)[0]==403,'Revoked module grant remains active'
 print('PASS: module state read, separate grants, global-scope enforcement, CSRF, core protection, stale/body bounds, no-op audit and immediate revocation')
finally:
 try:write(c,'/auth/logout','POST',{})
 except Exception:pass
 sql("DELETE FROM platform_audit WHERE actor='%s'; DELETE FROM platform_user_role WHERE user_id='%s'; DELETE FROM platform_role WHERE id='%s'; DELETE FROM platform_user WHERE id='%s'"%(name,owner,role,owner))
