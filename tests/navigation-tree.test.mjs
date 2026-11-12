import test from 'node:test';
import assert from 'node:assert/strict';
import {flattenNavigation,buildNavigationTree,filterNavigationTree,collectExpandableKeys} from '../plugins/navigation-console/frontend/navigation-tree.ts';
const row=(id,parentId=null,sortOrder=0,deleted=false)=>({id,parentId,sortOrder,deleted});
test('navigation retains sibling ordering, ignores deleted branches and keeps matching ancestors',()=>{
 const rows=[row('root'),row('b','root',2),row('a','root',1),row('removed',null,0,true),row('orphan','removed')];
 assert.deepEqual(flattenNavigation(rows).map(({id,level})=>[id,level]),[['root',0],['a',1],['b',1]]);
 const tree=buildNavigationTree(rows),filtered=filterNavigationTree(tree,n=>n.id==='b');
 assert.equal(tree[0].children.length,2);assert.equal(filtered[0].children[0].id,'b');
 assert.deepEqual(collectExpandableKeys(filtered),['root']);
 assert.deepEqual(filterNavigationTree(tree,()=>false),[]);
});
test('navigation traversal terminates for corrupt cycles',()=>{
 const rows=[row('root'),row('child','root'),row('root','child'),row('isolated','isolated')];
 assert.deepEqual(flattenNavigation(rows).map(n=>n.id),['root','child']);
 assert.deepEqual(collectExpandableKeys(buildNavigationTree(rows)),['root']);
});
test('twenty thousand menu levels can be flattened, built, filtered and expanded without recursion',()=>{
 const rows=Array.from({length:20000},(_,i)=>row(String(i),i?String(i-1):null));
 const flat=flattenNavigation(rows);assert.equal(flat.length,20000);assert.equal(flat.at(-1).level,19999);
 const tree=buildNavigationTree(rows),filtered=filterNavigationTree(tree,n=>n.id==='19999');
 assert.equal(collectExpandableKeys(filtered).length,19999);
 let nodes=filtered,count=0;while(nodes.length){assert.equal(nodes.length,1);count++;nodes=nodes[0].children ?? [];}
 assert.equal(count,20000);assert.equal(tree[0].id,'0');
});
