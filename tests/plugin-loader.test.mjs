import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
const source = stripTypeScriptTypes(readFileSync('apps/web/src/platform/modules/plugin-loader.ts', 'utf8'));
const { createPluginLoader } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
function fixture() {
 const nodes=[]; const registrations=[]; const removals=[];
 const document={currentScript:null,createElement(tag){return {tag,remove(){this.removed=true;}};},head:{appendChild(node){nodes.push(node);}}};
 const loader=createPluginLoader({document,assetUrl:(d,p)=>`${d}/${p}`,register:m=>{registrations.push(m);return()=>removals.push(m);},changed(){}});
 const plugin=(digest='a',version='1')=>({id:'sample',digest,version,installed:true,enabled:true,status:'RUNNING',manifest:{routes:[{id:'sample'}],files:{'frontend/style.css':'hash'}}});
 const bundle=(version='1')=>({sample:{id:'sample',version,routes:[{id:'sample'}]}});
 const sync=(p)=>loader.synchronize(p).map(a=>({...a,promise:a.promise.catch(e=>e.message)}));
 const script=()=>nodes.filter(n=>n.tag==='script').at(-1);
 const register=(s,b=bundle())=>{document.currentScript=s;loader.register('sample',b);document.currentScript=null;};
 return {loader,nodes,registrations,removals,plugin,bundle,sync,script,register};
}
test('uninstalled pending plugin cannot register late; reinstall is a distinct attempt',async()=>{
 const f=fixture();const [a]=f.sync([f.plugin()]);const old=f.script();f.sync([]);assert.equal(await a.promise,'pluginFrontendCancelled');
 const [b]=f.sync([f.plugin()]);f.register(old);assert.equal(f.registrations.length,0);f.register(f.script());await b.promise;assert.equal(f.registrations.length,1);
 f.loader.dispose();assert.equal(f.removals.length,1);assert.ok(f.nodes.every(n=>n.removed));
});
test('old digest callbacks and events cannot claim or reject replacement',async()=>{
 const f=fixture();const [a]=f.sync([f.plugin()]);const old=f.script(),error=old.onerror,load=old.onload;
 const [b]=f.sync([f.plugin('b')]);f.register(old);error();load();assert.equal(await a.promise,'pluginFrontendCancelled');assert.equal(f.registrations.length,0);
 f.register(f.script());await b.promise;assert.equal(f.registrations.length,1);f.loader.dispose();
});
test('incompatible and missing bundles clean resources and can retry',async()=>{
 const f=fixture();let [a]=f.sync([f.plugin()]);f.register(f.script(),f.bundle('2'));assert.equal(await a.promise,'pluginFrontendIncompatible');assert.ok(f.nodes.every(n=>n.removed));
 [a]=f.sync([f.plugin()]);f.script().onload();assert.equal(await a.promise,'pluginFrontendMissing');
 [a]=f.sync([f.plugin()]);f.loader.register('sample',f.bundle());assert.equal(f.registrations.length,0);f.register(f.script());f.register(f.script());await a.promise;assert.equal(f.registrations.length,1);f.loader.dispose();
});
test('same active manifest does not reload; version changes and dispose invalidate attempts',async()=>{
 const f=fixture();const [a]=f.sync([f.plugin()]);assert.deepEqual(f.sync([f.plugin()]),[]);f.register(f.script());await a.promise;
 const [b]=f.sync([f.plugin('a','2')]);assert.equal(f.removals.length,1);const old=f.script();f.loader.dispose();f.register(old,f.bundle('2'));assert.equal(await b.promise,'pluginFrontendCancelled');assert.equal(f.registrations.length,1);
 assert.deepEqual(f.sync([{...f.plugin(),enabled:false}]),[]);assert.deepEqual(f.sync([{...f.plugin(),status:'STOPPED'}]),[]);
});
