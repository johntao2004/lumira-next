"""Account-bound security history, pagination and password audit rollback."""
import json,secrets,uuid
from smoke_support import sql,client,call,login,write,config
name='qa_security_'+uuid.uuid4().hex;owner=str(uuid.uuid4());other=str(uuid.uuid4());tag='qa_sec_'+uuid.uuid4().hex
c=client();new_password=secrets.token_urlsafe(24)
try:
 sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Security QA',password_hash FROM platform_user WHERE username='admin'"%(owner,name))
 assert login(c,name,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
 assert login(client(),name,'wrong-password')==401
 assert call(client(),'/account/security-events')[0]==401
 sql("INSERT INTO platform_audit(id,actor,action,target_id) SELECT gen_random_uuid(),'fixture','account.emailVerified','%s' FROM generate_series(1,105)"%owner)
 # Foreign, unrelated actions, and an old account's same-name session history must stay hidden.
 sql("INSERT INTO platform_audit(id,actor,action,target_id,created_at) VALUES(gen_random_uuid(),'%s','session.revoke','%s',now()-interval '1 day'),(gen_random_uuid(),'%s','user.update','%s',now()),(gen_random_uuid(),'foreign','account.emailVerified','%s',now())"%(name,owner,name,owner,other))
 status,body=call(c,'/account/security-events?pageSize=100');first=json.loads(body);assert status==200 and first['total']==107 and len(first['items'])==100
 second=json.loads(call(c,'/account/security-events?page=2&pageSize=100')[1]);assert len(second['items'])==7 and not({v['id'] for v in first['items']} & {v['id'] for v in second['items']})
 assert {'account.loginSucceeded','account.loginFailed'} <= {v['action'] for v in first['items']+second['items']}
 assert all(set(v)=={'id','action','createdAt','clientAddress','userAgent'} for v in first['items'])
 assert all(v['clientAddress'] and v['userAgent'] for v in first['items']+second['items'] if v['action'].startswith('account.login'))
 assert all(v['clientAddress'] is None and v['userAgent'] is None for v in first['items'] if v['action']=='account.emailVerified')
 assert call(c,'/account/security-events?pageSize=101')[0]==400
 assert call(c,'/account/security-events?page=0')[0]==400
 sql("CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.actor='%s' AND NEW.action='account.passwordChanged' THEN RAISE EXCEPTION 'Fixture failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER %s BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION %s()"%(tag,name,tag,tag))
 payload={'currentPassword':config['LUMIRA_BOOTSTRAP_PASSWORD'],'newPassword':new_password}
 assert write(c,'/account/password','POST',payload)[0]>=500
 assert call(c,'/auth/me')[0]==200,'Session deletion not rolled back'
 sql('DROP TRIGGER %s ON platform_audit; DROP FUNCTION %s()'%(tag,tag))
 assert write(c,'/account/password','POST',payload)[0]==204
 assert login(c,name,new_password)==200
 events=json.loads(call(c,'/account/security-events')[1]);assert events['total']==109 and events['items'][0]['action']=='account.loginSucceeded'
 assert any(v['action']=='account.passwordChanged' for v in events['items'])
 sql("CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.actor='%s' AND NEW.action='account.loginSucceeded' THEN RAISE EXCEPTION 'Fixture failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER %s BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION %s()"%(tag,name,tag,tag))
 denied=client();assert login(denied,name,new_password)==503
 assert call(denied,'/auth/me')[0]==401,'Failed audit must not leave an authenticated session'
 print('PASS: own-account security history, old-name session exclusion, pagination, safe fields, bounds and password/session/audit atomic rollback')
finally:
 try:write(c,'/auth/logout','POST',{})
 except Exception:pass
 sql('DROP TRIGGER IF EXISTS %s ON platform_audit; DROP FUNCTION IF EXISTS %s()'%(tag,tag))
 sql("DELETE FROM platform_audit WHERE target_id IN ('%s','%s') OR actor='%s'; DELETE FROM platform_user WHERE id='%s'"%(owner,other,name,owner))
