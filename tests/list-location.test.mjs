import test from 'node:test';
import assert from 'node:assert/strict';
import { readListLocation,locationId } from '../apps/web/src/platform/routing/list-location.ts';
test('untrusted list addresses enforce API bounds and isolate nested lists',()=>{
  assert.deepEqual(readListLocation(new URLSearchParams('page=2&pageSize=50&search=test&items.page=3')),{page:2,pageSize:50,search:'test'});
  assert.deepEqual(readListLocation(new URLSearchParams('page=2&items.page=3'),'items.'),{page:3,pageSize:20,search:''});
  for(const value of ['-1','0','1.5','NaN','Infinity','9999999999999999999','2x'])assert.equal(readListLocation(new URLSearchParams({page:value})).page,1);
  assert.equal(readListLocation(new URLSearchParams('pageSize=101')).pageSize,20);
  assert.equal(readListLocation(new URLSearchParams({search:'x'.repeat(101)})).search.length,100);
  assert.equal(locationId('a/b'),null);assert.equal(locationId('constructor'),null);
  assert.equal(locationId('11111111-1111-4111-8111-111111111111'),'11111111-1111-4111-8111-111111111111');
});
