import assert from 'node:assert/strict';
import test from 'node:test';
import { initializeHostPages,registerPluginPages,frontendRouteOwner } from '../apps/web/src/platform/modules/page-store.ts';
test('host composition initializes once before dynamic registration without dropping registrations',()=>{
 const plugin={id:'sample',version:'1',required:false,dependencies:[],routes:[],pages:{}};
 assert.throws(()=>registerPluginPages(plugin),/not initialized/);
 initializeHostPages([]);
 const remove=registerPluginPages(plugin);
 assert.throws(()=>initializeHostPages([]),/already initialized/);
 assert.throws(()=>registerPluginPages(plugin),/Duplicate plugin/);
 remove();assert.doesNotThrow(()=>registerPluginPages(plugin)());
 assert.equal(frontendRouteOwner('missing'),null);
});
