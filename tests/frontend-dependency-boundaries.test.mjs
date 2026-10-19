import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { assertPluginRelativeImport } from '../scripts/frontend-plugin-boundaries.mjs';
test('relative plugin imports cannot bypass host SDK or cross plugin ownership',()=>{
 const check = (specifier)=>assertPluginRelativeImport('/repo','/repo/plugins/one','/repo/plugins/one/frontend/module.tsx',specifier);
 assert.doesNotThrow(()=>check('./page'));
 assert.doesNotThrow(()=>check('../../sdk/frontend/api'));
 for(const value of ['../../../apps/web/src/features/account/module','../../two/frontend/module','../../sdk/private']) assert.throws(()=>check(value),/escapes/);
 assert.doesNotThrow(()=>assertPluginRelativeImport('/repo','/repo/plugins/sdk/examples/sample','/repo/plugins/sdk/examples/sample/frontend/module.tsx','../../../frontend/api'));
});
test('shared page storage and authentication do not import the host composition root',()=>{
 for(const file of ['page-store.ts','plugin-loader.ts','plugin-runtime.ts']){
  const source=readFileSync(`apps/web/src/platform/modules/${file}`,'utf8');
  assert.doesNotMatch(source,/from\s+['"][^'"]*(?:features\/|app\/|registry)/);
 }
 assert.doesNotMatch(readFileSync('apps/web/src/platform/auth/return-path.ts','utf8'),/shell\/routes/);
 assert.match(readFileSync('apps/web/src/app/dashboard-modules.tsx','utf8'),/initializeHostPages/);
});
