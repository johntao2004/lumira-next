"""Real single-instance lifecycle acceptance. Uses only qa-plugin-* fixtures; preserves business data.
Run after pnpm plugins:build and starting 8010. Requires JDK 25 and the local test database.
"""
from pathlib import Path
import io,json,hashlib,zipfile,subprocess,os,time,uuid,threading
from smoke_support import client,call,login,write,token,sql,config,wait_for_readiness
ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'.runtime/plugin-work/fixtures';WORK.mkdir(parents=True,exist_ok=True)
JAVA=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home'))/'bin'
admin=client();assert login(admin,'admin',config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
P='/platform/modules/plugins';fixture_ids=[]
def jsoncall(path,method='GET',payload=None):
 status,body=call(admin,path) if method=='GET' else write(admin,path,method,payload or {})
 assert status==200,(path,status,body.decode());return json.loads(body)
def error_code(body):
 payload=json.loads(body)
 return payload.get('code') or payload.get('detail')
def imports(data,expected=200):
 status,body=call(admin,P+'/import','POST',data,{**token(admin),'Content-Type':'application/octet-stream'})
 assert status==expected,(status,body.decode());return json.loads(body)
def operate(id,kind,expected='SUCCEEDED'):
 plan=jsoncall(f'{P}/{id}/{kind}-plan','POST');assert not plan['blockers'],plan
 op=jsoncall(f'{P}/{id}/{kind}','POST',{'revision':plan['revision']})
 for _ in range(200):
  op=jsoncall(P+'/operations/'+op['id'])
  if op['status'] in ['SUCCEEDED','FAILED']:break
  time.sleep(.1)
 assert op['status']==expected,op;wait_for_readiness();return op

def zipbytes(files):
 out=io.BytesIO()
 with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
  for name,data in sorted(files.items()):z.writestr(zipfile.ZipInfo(name,(2020,1,1,0,0,0)),data)
 return out.getvalue()
def modify(data,mutation):
 with zipfile.ZipFile(io.BytesIO(data)) as z:files={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
 manifest=json.loads(files.pop('plugin.json'));mutation(manifest,files)
 manifest['files']={n:hashlib.sha256(v).hexdigest() for n,v in files.items()};files['plugin.json']=json.dumps(manifest,sort_keys=True).encode();return zipbytes(files)
def fixture(id,deps=(),bundles=(),delay=0,fail=False,missing=False,private=False,protected=False,api_path=None):
 fixture_ids.append(id);dir=WORK/id;src=dir/'src';classes=dir/'classes';src.mkdir(parents=True,exist_ok=True);classes.mkdir(exist_ok=True)
 endpoint=api_path or '/api/plugins/'+id+'/status'
 package='com.lumira.plugins.'+id.replace('-','_');entry=package+'.ProbePlugin'
 mutation='@PostMapping("/api/plugins/'+id+'/mutate") public java.util.Map<String,String> mutate(){return java.util.Map.of("code","authorized");}' if protected else ''
 resources=''
 if id=='qa-plugin-slow':
  marker=WORK/'tasks.log';closed=WORK/'closed.log';marker.unlink(missing_ok=True);closed.unlink(missing_ok=True)
  resources=f'@Bean Object task(com.lumira.plugin.sdk.PluginResources r){{r.schedule("tick",java.time.Duration.ofMillis(30),()->{{try{{java.nio.file.Files.writeString(java.nio.file.Path.of("{marker}"),"tick\\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);}}catch(Exception e){{throw new RuntimeException(e);}}}});r.own("listener",()->java.nio.file.Files.writeString(java.nio.file.Path.of("{closed}"),"closed"));return new Object();}}'
 source=f'''package {package};
import com.lumira.plugin.sdk.PluginEntry;import com.lumira.platform.modules.ModuleEndpoint;import org.pf4j.PluginWrapper;import org.springframework.context.annotation.*;import org.springframework.web.bind.annotation.*;
public class ProbePlugin extends PluginEntry {{public ProbePlugin(PluginWrapper w){{super(w);}}public Class<?> configuration(){{return Config.class;}}public void checkHealth(){{try{{Thread.sleep({delay});}}catch(InterruptedException e){{Thread.currentThread().interrupt();throw new IllegalStateException(e);}}}}
@Configuration(proxyBeanMethods=false) public static class Config {{@Bean ProbeController probe(){{return new ProbeController();}} {resources}}}
@ModuleEndpoint("{id}") @RestController public static class ProbeController {{@GetMapping("{endpoint}") public java.util.Map<String,String> status(@RequestParam(defaultValue="0") int delay)throws Exception{{Thread.sleep(Math.min(delay,5000));return java.util.Map.of("code",{"com.example.qa.api.PrivateValue.value()" if private else '"new-runtime-code"'},"loader",getClass().getClassLoader().getClass().getName());}} {mutation} {"public Object missing(){return new MissingClass();}" if missing else ''}}}
}}'''
 (src/'ProbePlugin.java').write_text(source)
 if missing:(src/'MissingClass.java').write_text(f'package {package};public class MissingClass {{}}')
 cp=str(ROOT/'backend/target/classes')+os.pathsep+(ROOT/'backend/target/plugin-classpath.txt').read_text().strip()
 libs={}
 if private:
  private_src=dir/'PrivateValue.java';private_src.write_text('package com.example.qa.api;public class PrivateValue {public static String value(){return "private-library-loaded";}}')
  libclasses=dir/'libclasses';libclasses.mkdir(exist_ok=True)
  subprocess.run([str(JAVA/'javac'),'-d',str(libclasses),str(private_src)],check=True,capture_output=True)
  libjar=dir/'private.jar';subprocess.run([str(JAVA/'jar'),'--create','--date','2020-01-01T00:00:00Z','--file',str(libjar),'-C',str(libclasses),'.'],check=True,capture_output=True)
  libs['backend/lib/private.jar']=libjar.read_bytes();cp+=os.pathsep+str(libjar)
 subprocess.run([str(JAVA/'javac'),'--release','25','-parameters','-cp',cp,'-d',str(classes),*[str(p) for p in src.glob('*.java')]],check=True,capture_output=True)
 if missing:(classes/Path(package.replace('.','/'))/'MissingClass.class').unlink()
 mf=dir/'MANIFEST.MF';mf.write_text(f'Manifest-Version: 1.0\nPlugin-Id: {id}\nPlugin-Version: 1.0.0\nPlugin-Class: {entry}\n\n')
 jar=dir/'plugin.jar';subprocess.run([str(JAVA/'jar'),'--create','--date','2020-01-01T00:00:00Z','--file',str(jar),'--manifest',str(mf),'-C',str(classes),'.'],check=True,capture_output=True)
 probe=ROOT/'plugins/sdk/examples/hotplug-probe/dist/package/frontend/plugin.js'
 assert probe.exists(),'Build probe with pnpm plugins:build sdk/examples/hotplug-probe'
 files={'backend/plugin.jar':jar.read_bytes(),'frontend/plugin.js':probe.read_bytes().replace(b'hotplug-probe',id.encode()),'migrations/V1__fixture.sql':b'SELECT 1;' if not fail else b'INVALID MIGRATION;'};files.update(libs)
 for other,data in bundles:files['dependencies/'+other+'.lumira-plugin.zip']=data
 manifest={'formatVersion':1,'id':id,'name':id,'version':'1.0.0','sdkVersion':'1','entryClass':entry,'backend':'backend/plugin.jar','frontend':'frontend/plugin.js','dependencies':[{'id':'platform','version':'1.0.0'},*[{'id':d,'version':'1.0.0'} for d in deps]],'permissions':[],'routes':[{'id':id,'label':id,'permission':None,'group':'operations','order':999,'global':False}],'apiRules':[{'method':'GET','path':endpoint,'authorities':[],'publicAccess':True,'global':False}],'files':{n:hashlib.sha256(v).hexdigest() for n,v in files.items()}}
 if protected:
  perm=f'platform:{id}:read';manifest['permissions']=[perm];manifest['routes'][0]['permission']=perm;manifest['apiRules'][0].update(authorities=[perm],publicAccess=False);manifest['apiRules'].append({'method':'POST','path':f'/api/plugins/{id}/mutate','authorities':[perm],'publicAccess':False,'global':True})
 files['plugin.json']=json.dumps(manifest,sort_keys=True).encode();data=zipbytes(files);(dir/(id+'.lumira-plugin.zip')).write_bytes(data);return data

def main():
 before_pid=subprocess.check_output(['node','scripts/status.mjs'],cwd=ROOT,text=True).splitlines()[:2]
 baseline=sql("SELECT md5(string_agg(row_to_json(t)::text,'' ORDER BY id)) FROM platform_sensitive_word t WHERE NOT deleted")
 try:
  # Every existing plugin really removes its endpoints/assets and restores persistent data.
  for id,path in [('feedback','/feedback'),('localization','/platform/localization/entries'),('sensitive-words','/platform/sensitive-words')]:
   view=next(x for x in jsoncall(P) if x['id']==id);original=view['enabled'];assert view['installed']
   if not original:
    row=next(x for x in jsoncall('/platform/modules') if x['id']==id);jsoncall('/platform/modules/'+id+'/state','PUT',{'enabled':True,'version':row['stateVersion'],'reason':'Plugin smoke'})
   status,body=call(admin,path+'?pageSize=100');assert status==200;before=json.loads(body)
   operate(id,'uninstall');assert call(admin,path)[0] in [403,404,503]
   assert call(admin,P+'/assets/'+view['digest']+'/plugin.js')[0]==404
   operate(id,'install');assert json.loads(call(admin,path+'?pageSize=100')[1])==before,'Data changed on reinstall: '+id
   if not original:
    row=next(x for x in jsoncall('/platform/modules') if x['id']==id);jsoncall('/platform/modules/'+id+'/state','PUT',{'enabled':False,'version':row['stateVersion'],'reason':'Plugin smoke restore'})
  print('PASS: three plugins uninstall/reinstall preserve data; HTTP/assets withdrawn')
  # Imported code, offline transitive dependencies, private libraries and sharing.
  lib=fixture('qa-plugin-lib',private=True);a=fixture('qa-plugin-a',['qa-plugin-lib'],[('qa-plugin-lib',lib)]);b=fixture('qa-plugin-b',['qa-plugin-lib'])
  imports(a);imports(a);imports(b);operate('qa-plugin-a','install');operate('qa-plugin-b','install')
  assert json.loads(call(client(),'/plugins/qa-plugin-lib/status')[1])['code']=='private-library-loaded'
  assert 'PluginRuntime' in json.loads(call(client(),'/plugins/qa-plugin-a/status')[1])['loader']
  plan=jsoncall(P+'/qa-plugin-lib/uninstall-plan','POST');assert 'qa-plugin-a' in str(plan['blockers']) and 'qa-plugin-b' in str(plan['blockers'])
  row=next(x for x in jsoncall('/platform/modules') if x['id']=='qa-plugin-b');jsoncall('/platform/modules/qa-plugin-b/state','PUT',{'enabled':False,'version':row['stateVersion'],'reason':'Stopped consumer still retains dependency'})
  assert 'qa-plugin-b' in str(jsoncall(P+'/qa-plugin-lib/uninstall-plan','POST')['blockers'])
  operate('qa-plugin-a','uninstall');assert next(x for x in jsoncall(P) if x['id']=='qa-plugin-lib')['installed']
  operate('qa-plugin-b','uninstall');assert not next(x for x in jsoncall(P) if x['id']=='qa-plugin-lib')['installed']
  operate('qa-plugin-lib','install');operate('qa-plugin-a','install');operate('qa-plugin-a','uninstall');assert next(x for x in jsoncall(P) if x['id']=='qa-plugin-lib')['installed'];operate('qa-plugin-lib','uninstall')
  print('PASS: complete offline dependencies, private JAR, stopped reverse consumers, shared/explicit retention, automatic cleanup')
  # In-flight requests drain; serialized operations and state revisions reject races.
  slow=fixture('qa-plugin-slow',delay=1500);imports(slow);plan=jsoncall(P+'/qa-plugin-slow/install-plan','POST');csrf={**token(admin),'Content-Type':'application/json'};op=jsoncall(P+'/qa-plugin-slow/install','POST',{'revision':plan['revision']})
  # A concurrent write may reach the busy guard or the runtime readiness filter.
  status,body=call(admin,P+'/qa-plugin-slow/install','POST',json.dumps({'revision':plan['revision']}).encode(),csrf)
  if status==409:
   assert error_code(body) in ['pluginBusy','staleRecord'],(status,body.decode(errors='replace'))
  else:
   assert status==503 and error_code(body)=='pluginRuntimeNotReady',(status,body.decode(errors='replace'))
  while op['status'] in ['QUEUED','RUNNING']:
   time.sleep(.1);op=jsoncall(P+'/operations/'+op['id'])
  assert op['status']=='SUCCEEDED',op;wait_for_readiness()
  # The completed graph revision must reject the stale pre-install plan.
  status,body=call(admin,P+'/qa-plugin-slow/install','POST',json.dumps({'revision':plan['revision']}).encode(),csrf)
  assert status==409 and error_code(body)=='staleRecord',(status,body.decode(errors='replace'))
  inflight=[];thread=threading.Thread(target=lambda:inflight.append(call(client(),'/plugins/qa-plugin-slow/status?delay=1800')));thread.start();time.sleep(.2);t=time.monotonic();operate('qa-plugin-slow','uninstall');thread.join();assert inflight[0][0]==200 and time.monotonic()-t>1
  assert call(client(),'/plugins/qa-plugin-slow/status')[0] in [401,403,404]
  assert (WORK/'closed.log').read_text()=='closed';ticks=(WORK/'tasks.log').read_bytes();time.sleep(.2);assert (WORK/'tasks.log').read_bytes()==ticks,'Task remained scheduled after unloading'
  for _ in range(2):operate('qa-plugin-slow','install');operate('qa-plugin-slow','uninstall')
  print('PASS: concurrency/version guards, request draining, repeated lifecycle cleanup')
  protected=fixture('qa-plugin-protected',protected=True);imports(protected);operate('qa-plugin-protected','install')
  owner=str(uuid.uuid4());username='qa_plugin_'+uuid.uuid4().hex;ordinary=client();role=str(uuid.uuid4())
  try:
   sql("INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '%s','%s','Plugin QA',password_hash FROM platform_user WHERE username='admin'"%(owner,username));assert login(ordinary,username,config['LUMIRA_BOOTSTRAP_PASSWORD'])==200
   path='/plugins/qa-plugin-protected/status';perm='platform:qa-plugin-protected:read'
   assert call(client(),path)[0]==401 and call(ordinary,path)[0]==403
   sql("INSERT INTO platform_role(id,code,name,enabled,data_scope) VALUES('%s','%s','Plugin QA',true,'SELF');INSERT INTO platform_role_authority VALUES('%s','%s');INSERT INTO platform_user_role VALUES('%s','%s')"%(role,username,role,perm,owner,role))
   assert call(ordinary,path)[0]==200
   mutate='/plugins/qa-plugin-protected/mutate';assert write(ordinary,mutate,'POST',{})[0]==403
   sql("UPDATE platform_role SET data_scope='ALL' WHERE id='%s'"%role)
   assert call(ordinary,mutate,'POST',b'{}',{'Content-Type':'application/json'})[0]==403
   assert write(ordinary,mutate,'POST',{})[0]==200
   sql("DELETE FROM platform_role_authority WHERE role_id='%s'"%role);assert call(ordinary,path)[0]==403
  finally:
   sql("DELETE FROM platform_user_role WHERE user_id='%s';DELETE FROM platform_role WHERE id='%s';DELETE FROM platform_user WHERE id='%s'"%(owner,role,owner));operate('qa-plugin-protected','uninstall')
  print('PASS: imported permissions default deny, authentication, CSRF, data scope and immediate grant revocation')
  # Import failures and preflight collisions; migration failure never exposes runtime capabilities.
  imports(fixture('qa-plugin-missing',missing=True),400)
  traversal=zipbytes({'../escape':b'x'});imports(traversal,400)
  cdata=fixture('qa-plugin-cycle-c',['qa-plugin-cycle-d']);ddata=fixture('qa-plugin-cycle-d',['qa-plugin-cycle-c']);imports(modify(cdata,lambda m,f:f.__setitem__('dependencies/d.lumira-plugin.zip',ddata)),400)
  imports(modify(a,lambda m,f:f.__setitem__('frontend/plugin.js',b'corrupt')),409) # same version different content
  imports(fixture('qa-plugin-missing-dep',['absent-plugin']),400)
  imports(modify(fixture('qa-plugin-version'),lambda m,f:m['dependencies'].__setitem__(0,{'id':'platform','version':'99.0.0'})),400)
  bad=fixture('qa-plugin-fail',fail=True);imports(bad);failed=operate('qa-plugin-fail','install','FAILED');assert sql("SELECT count(*) FROM platform_audit WHERE action='plugin.installFailed' AND target_id='%s'"%failed['id'])=='1';assert call(admin,'/plugins/qa-plugin-fail/status')[0]!=200
  collision=fixture('qa-plugin-collision');imports(modify(collision,lambda m,f:m['routes'][0].__setitem__('id','modules')));assert jsoncall(P+'/qa-plugin-collision/install-plan','POST')['blockers']
  imports(fixture('qa-plugin-api',api_path='/api/platform/modules'));assert 'pluginEndpointConflict' in str(jsoncall(P+'/qa-plugin-api/install-plan','POST')['blockers'])
  print('PASS: missing classes, immutable version conflicts, missing/version dependencies, route conflict, failed migration rollback')
  assert sql("SELECT md5(string_agg(row_to_json(t)::text,'' ORDER BY id)) FROM platform_sensitive_word t WHERE NOT deleted")==baseline
  assert subprocess.check_output(['node','scripts/status.mjs'],cwd=ROOT,text=True).splitlines()[:2]==before_pid,'Platform restarted during hotplug'
  print('PASS: original word baseline unchanged; backend/frontend process IDs unchanged')
 finally:
  wait_for_readiness()
  for id in reversed(list(dict.fromkeys(fixture_ids))):
   row=next((x for x in jsoncall(P) if x['id']==id),None)
   if row and row['installed']:operate(id,'uninstall')

if __name__=='__main__':main()
