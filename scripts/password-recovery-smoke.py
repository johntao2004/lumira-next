"""Anonymous recovery API proof using isolated accounts; never enqueue or send mail."""
import hashlib,json,secrets,uuid
from smoke_support import sql,client,call,login,write,config
prefix='qa_recovery_http_'+uuid.uuid4().hex
owner=str(uuid.uuid4());name=prefix+'owner';unknown=prefix+'unknown';limited=prefix+'limited'
anon,one,two=client(),client(),client()
base='/auth/password-recovery'
try:
 sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Recovery QA',password_hash FROM platform_user WHERE username='admin'"%(owner,name))
 assert call(anon,base+'/confirm','POST',b'{}',{'Content-Type':'application/json'})[0]==403
 # No fixture has a verified address during request checks: no producer can enqueue mail.
 absent=write(anon,base+'/request','POST',{'username':unknown})
 unverified=write(anon,base+'/request','POST',{'username':name})
 sql("UPDATE platform_user SET enabled=false WHERE id='%s'"%owner)
 disabled=write(anon,base+'/request','POST',{'username':name})
 assert absent[0]==unverified[0]==disabled[0] and absent[0] in (202,503)
 if absent[0]==202:assert absent[1]==unverified[1]==disabled[1]==b''
 else:assert json.loads(absent[1])['detail']==json.loads(unverified[1])['detail']==json.loads(disabled[1])['detail']
 sql("UPDATE platform_user SET enabled=true WHERE id='%s'"%owner)
 assert login(one,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
 assert login(two,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
 code=secrets.token_urlsafe(32);digest=hashlib.sha256(code.encode()).hexdigest();new_password=secrets.token_urlsafe(24)
 sql("INSERT INTO platform_account_email(user_id,email,verified_at) VALUES('%s','%s@example.invalid',now())"%(owner,name))
 sql("INSERT INTO platform_password_reset(user_id,token_hash,password_snapshot,email_version,expires_at,requested_at,request_day,request_count) SELECT id,'%s',password_hash,0,now()+interval '10 minutes',now(),CURRENT_DATE,1 FROM platform_user WHERE id='%s'"%(digest,owner))
 assert write(anon,base+'/confirm','POST',{'username':name,'token':'invalid','newPassword':new_password})[0]==400
 assert sql("SELECT failures FROM platform_password_reset WHERE user_id='%s'"%owner)=='1'
 assert write(anon,base+'/confirm','POST',{'username':name,'token':code,'newPassword':config['LUMIRA_BOOTSTRAP_PASSWORD']})[0]==400
 assert write(anon,base+'/confirm','POST',{'username':name,'token':code,'newPassword':new_password})[0]==204
 assert call(one,'/auth/me')[0]==401 and call(two,'/auth/me')[0]==401
 assert write(anon,base+'/confirm','POST',{'username':name,'token':code,'newPassword':new_password})[0]==400
 assert login(one,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==401
 assert login(one,name,new_password)==200
 key='recovery-user:'+hashlib.sha256(limited.encode()).hexdigest()
 sql("INSERT INTO platform_rate_bucket(bucket_key,window_start,attempts) VALUES('%s',date_trunc('minute',now()),10)"%key)
 assert write(anon,base+'/confirm','POST',{'username':limited,'token':'bad','newPassword':new_password})[0]==429
 assert sql("SELECT attempts FROM platform_rate_bucket WHERE bucket_key='%s'"%key)=='11'
 assert sql("SELECT count(*) FROM platform_audit WHERE actor='%s' AND action='account.passwordRecovered'"%name)=='1'
 print('PASS: anonymous CSRF, uniform ineligible request status, committed failed proofs, reuse rejection, password reset, two sessions revoked, token replay, old/new login and limiter; no email sent')
finally:
 for c in [one,two,anon]:
  try:write(c,'/auth/logout','POST',{})
  except Exception:pass
 sql("DELETE FROM platform_audit WHERE actor='%s'; DELETE FROM platform_user WHERE id='%s'"%(name,owner))
 for account in [name,unknown,limited]:sql("DELETE FROM platform_rate_bucket WHERE bucket_key='recovery-user:%s'"%hashlib.sha256(account.encode()).hexdigest())
