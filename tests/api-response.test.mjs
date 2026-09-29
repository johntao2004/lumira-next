import test from 'node:test';
import assert from 'node:assert/strict';
import {api,ApiError} from '../apps/web/src/platform/api/client.ts';

test('API accepts empty successful responses and preserves JSON/error handling',async t=>{
 const responses=[new Response(null,{status:202}),new Response(null,{status:204}),new Response('{"version":1}',{status:200}),new Response('{"detail":"invalidRecoveryToken"}',{status:400}),new Response('invalid-json',{status:200})];
 t.mock.method(globalThis,'fetch',async()=>responses.shift());
 assert.equal(await api('/fixture'),undefined);
 assert.equal(await api('/fixture'),undefined);
 assert.deepEqual(await api('/fixture'),{version:1});
 await assert.rejects(api('/fixture'),e=>e instanceof ApiError&&e.status===400&&e.message==='invalidRecoveryToken');
 await assert.rejects(api('/fixture'),SyntaxError);
});
