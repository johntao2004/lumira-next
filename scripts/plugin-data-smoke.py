"""Uninstall data choices against disposable qa-plugin-data-* records only."""
import importlib.util,json,time,uuid
from pathlib import Path
from smoke_support import call,write,sql
spec=importlib.util.spec_from_file_location('plugin_smoke',Path(__file__).with_name('plugins-smoke.py'))
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)
P=smoke.P;admin=smoke.admin;ids=['qa-plugin-data-dep','qa-plugin-data-root','qa-plugin-data-rollback']
def table(id):return 'plugin_'+id.replace('-','_')+'_records'
def package(id,deps=(),bundles=(),rollback=False):
 def edit(m,files):
  t=table(id);m['dataSets']=[{'table':t,'label':'Fixture records'}]
  migration=f'CREATE TABLE {t}(id int PRIMARY KEY);INSERT INTO {t} VALUES(1);'
  if rollback:
   m['dataSets'].append({'table':t+'_blocked','label':'Blocked fixture'})
   migration+=f'CREATE TABLE {t}_blocked(id int PRIMARY KEY);CREATE TABLE {t}_ref(id int REFERENCES {t}_blocked);INSERT INTO {t}_blocked VALUES(1);INSERT INTO {t}_ref VALUES(1);'
  files['migrations/V1__fixture.sql']=migration.encode()
 return smoke.modify(smoke.fixture(id,deps,bundles),edit)
def plan(id,mode):return smoke.jsoncall(P+'/'+id+'/uninstall-plan?dataMode='+mode,'POST')
def execute(id,p,payload=None,expected='SUCCEEDED'):
 op=smoke.jsoncall(P+'/'+id+'/uninstall','POST',payload or {'revision':p['revision'],'dataMode':p['dataMode'],'confirmation':id})
 for _ in range(200):
  op=smoke.jsoncall(P+'/operations/'+op['id'])
  if op['status'] in ['SUCCEEDED','FAILED']:break
  time.sleep(.05)
 assert op['status']==expected,op
 return op
before=sql("SELECT md5(string_agg(row_to_json(t)::text,'' ORDER BY id)) FROM platform_sensitive_word t WHERE NOT deleted")
try:
 dep=package(ids[0]);root=package(ids[1],[ids[0]],[(ids[0],dep)]);smoke.imports(root);smoke.operate(ids[1],'install')
 for id in ids[:2]:assert sql('SELECT count(*) FROM '+table(id))=='1'
 p=plan(ids[1],'PRESERVE');execute(ids[1],p,{'revision':p['revision']})
 assert sql('SELECT count(*) FROM '+table(ids[1]))=='1' and sql('SELECT count(*) FROM '+table(ids[0]))=='1'
 smoke.operate(ids[1],'install');p=plan(ids[1],'DELETE');assert sum(x['count'] for x in p['data'])==1
 status,_=write(admin,P+'/'+ids[1]+'/uninstall','POST',{'revision':p['revision'],'dataMode':'DELETE'});assert status==400
 status,_=write(admin,P+'/'+ids[1]+'/uninstall','POST',{'revision':plan(ids[1],'PRESERVE')['revision'],'dataMode':'DELETE','confirmation':ids[1]});assert status==409
 sql('INSERT INTO '+table(ids[1])+' VALUES(2)')
 status,_=write(admin,P+'/'+ids[1]+'/uninstall','POST',{'revision':p['revision'],'dataMode':'DELETE','confirmation':ids[1]});assert status==409
 row=next(x for x in smoke.jsoncall('/platform/modules') if x['id']==ids[1]);smoke.jsoncall('/platform/modules/'+ids[1]+'/state','PUT',{'enabled':False,'version':row['stateVersion'],'reason':'Test stopped plugin data deletion'})
 p=plan(ids[1],'DELETE');assert p['data'][0]['count']==2;op=execute(ids[1],p);assert op['dataMode']=='DELETE'
 assert sql('SELECT count(*) FROM '+table(ids[1]))=='0' and sql('SELECT count(*) FROM '+table(ids[0]))=='1'
 assert sql("SELECT count(*) FROM platform_audit WHERE action='plugin.dataDeleted' AND target_id='%s'"%op['id'])=='1'
 smoke.operate(ids[1],'install');assert sql('SELECT count(*) FROM '+table(ids[1]))=='0';assert sql('SELECT count(*) FROM plugin_'+ids[1].replace('-','_')+'_schema_history')!='0';smoke.operate(ids[1],'uninstall')
 print('PASS: default preserve, explicit delete, typed confirmation, mode/count revision guard, stopped cleanup, dependency data retained, audit and reinstall without data resurrection')
 smoke.imports(package(ids[2],rollback=True));smoke.operate(ids[2],'install');failed=execute(ids[2],plan(ids[2],'DELETE'),expected='FAILED');assert failed['errorCode']=='pluginDataDeletionFailed'
 assert sql('SELECT count(*) FROM '+table(ids[2]))=='1' and sql('SELECT count(*) FROM '+table(ids[2])+'_blocked')=='1'
 print('PASS: failing cleanup rolls back all data deletion')
 smoke.imports(smoke.modify(smoke.fixture('qa-plugin-data-invalid'),lambda m,f:m.update(dataSets=[{'table':'platform_user','label':'Users'}])),400)
 assert sql("SELECT md5(string_agg(row_to_json(t)::text,'' ORDER BY id)) FROM platform_sensitive_word t WHERE NOT deleted")==before
 print('PASS: invalid core data scope rejected; original sensitive word baseline unchanged')
finally:
 for id in ids:
  row=next((x for x in smoke.jsoncall(P) if x['id']==id),None)
  if row and row['installed']:smoke.operate(id,'uninstall')
 sql("DELETE FROM platform_plugin_operation WHERE module_id LIKE 'qa-plugin-data-%';DELETE FROM platform_plugin_installation WHERE module_id LIKE 'qa-plugin-data-%';DELETE FROM platform_plugin_artifact WHERE module_id LIKE 'qa-plugin-data-%';DELETE FROM platform_module_state WHERE module_id LIKE 'qa-plugin-data-%';DELETE FROM platform_navigation_menu WHERE module_id LIKE 'qa-plugin-data-%'")
 for id in ids:
  sql('DROP TABLE IF EXISTS '+table(id)+'_ref,'+table(id)+'_blocked,'+table(id)+',plugin_'+id.replace('-','_')+'_schema_history')
