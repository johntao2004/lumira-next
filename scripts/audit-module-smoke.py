"""Toggle the real audit console, preserving original state and core logging."""
import json
from smoke_support import client,call,login,write,sql,config
c=client();probe=client();original=None
assert login(c,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
def state():
 status,body=call(c,'/platform/modules');assert status==200
 return next(m for m in json.loads(body) if m['id']=='audit-console')
def change(enabled):
 row=state();status,body=write(c,'/platform/modules/audit-console/state','PUT',{'enabled':enabled,'version':row['stateVersion'],'reason':'Audit console HTTP verification'})
 assert status==200,(status,body)
try:
 original=state()['enabled'];change(True);assert call(c,'/platform/audit')[0]==200
 change(False);assert call(c,'/platform/audit')[0]==503
 assert 'audit-console' not in json.loads(call(c,'/auth/me')[1])['enabledModules']
 assert call(c,'/account/security-events')[0]==200
 before=int(sql("SELECT count(*) FROM platform_audit WHERE actor='admin' AND action='account.loginSucceeded'"))
 assert login(probe,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
 assert int(sql("SELECT count(*) FROM platform_audit WHERE actor='admin' AND action='account.loginSucceeded'"))>before
 assert call(probe,'/platform/audit')[0]==503
 change(True);assert call(c,'/platform/audit')[0]==200
 assert 'audit-console' in json.loads(call(c,'/auth/me')[1])['enabledModules']
 print('PASS: real audit module disable/enable, capability refresh, existing-session denial and continued core security logging')
finally:
 try:
  if original is not None:change(original)
 finally:
  for session in [c,probe]:
   try:write(session,'/auth/logout','POST',{})
   except Exception:pass
