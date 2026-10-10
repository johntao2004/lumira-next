import test from 'node:test';
import assert from 'node:assert/strict';
import { listLocationChanges, readListLocation,locationId } from '../apps/web/src/platform/routing/list-location.ts';
import { DEFAULT_TABLE_PAGE_SIZE } from '../apps/web/src/platform/api/pagination.ts';
test('untrusted list addresses enforce API bounds and isolate nested lists',()=>{
  assert.deepEqual(readListLocation(new URLSearchParams('page=2&pageSize=50&search=test&items.page=3')),{page:2,pageSize:50,search:'test'});
  assert.deepEqual(readListLocation(new URLSearchParams('page=2&items.page=3'),'items.'),{page:3,pageSize:DEFAULT_TABLE_PAGE_SIZE,search:''});
  for(const value of ['-1','0','1.5','NaN','Infinity','9999999999999999999','2x'])assert.equal(readListLocation(new URLSearchParams({page:value})).page,1);
  assert.equal(readListLocation(new URLSearchParams('pageSize=101')).pageSize,DEFAULT_TABLE_PAGE_SIZE);
  assert.equal(readListLocation(new URLSearchParams({search:'x'.repeat(101)})).search.length,100);
  assert.equal(locationId('a/b'),null);assert.equal(locationId('constructor'),null);
  assert.equal(locationId('11111111-1111-4111-8111-111111111111'),'11111111-1111-4111-8111-111111111111');
});

test('dictionary filter addresses retain modes and range bounds in each nested prefix',()=>{
  const params = new URLSearchParams('filter.name=base&filterMode.name=contains&items.filter.enabled=true&items.filterMode.enabled=exact&items.filter.createdAt.from=2026-01-01&items.filter.createdAt.to=2026-01-31&items.filterMode.createdAt=range');
  assert.deepEqual(readListLocation(params).filters,{name:'base'});
  assert.deepEqual(readListLocation(params).filterModes,{name:'contains'});
  assert.deepEqual(readListLocation(params,'items.').filters,{enabled:'true','createdAt.from':'2026-01-01','createdAt.to':'2026-01-31'});
  assert.deepEqual(readListLocation(params,'items.').filterModes,{enabled:'exact',createdAt:'range'});
});

test('URL filters enforce field/value limits and reject prototype names and invalid modes',()=>{
  const params = new URLSearchParams('filterMode.name=execute&filter.bad[]=x&filter.__proto__=polluted&filter.constructor=kept');
  params.set('filter.long','x'.repeat(513));
  for(let i=0;i<50;i++) params.set(`filter.field${i}`,'value');
  const result=readListLocation(params);
  assert.equal(result.filterModes?.name,undefined);
  assert.equal(result.filters?.long,undefined);
  assert.equal(Object.hasOwn(result.filters??{},'__proto__'),false);
  assert.equal(result.filters.constructor,'kept');
  assert.equal(Object.keys(result.filters).length,40);
  assert.equal({}.polluted,undefined);
});

test('filter updates remove cleared keys and preserve sibling list and unrelated state',()=>{
  const params=new URLSearchParams('filter.old=value&filterMode.old=exact&items.filter.keep=yes&editor=123');
  const changes=listLocationChanges(params,{page:1,pageSize:10,search:'',filters:{name:'中文'},filterModes:{name:'contains'}});
  for(const [key,value] of Object.entries(changes)) value===null?params.delete(key):params.set(key,value);
  assert.equal(params.has('filter.old'),false);
  assert.equal(params.has('filterMode.old'),false);
  assert.equal(params.get('filter.name'),'中文');
  assert.equal(params.get('filterMode.name'),'contains');
  assert.equal(params.get('items.filter.keep'),'yes');
  assert.equal(params.get('editor'),'123');
});
