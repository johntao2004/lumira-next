import type { NavigationMenu } from "../../../apps/web/src/platform/navigation/api-types.ts";
export type MenuRow = NavigationMenu & { level: number };
export type NavigationTreeRow = NavigationMenu & { children?: NavigationTreeRow[] };

/** One index and active path; neither recursion nor a copied path per descendant. */
export function flattenNavigation(rows: readonly NavigationMenu[]): MenuRow[] {
  const byParent = new Map<string | null, NavigationMenu[]>();
  for (const row of rows) {
    if (row.deleted) continue;
    const siblings = byParent.get(row.parentId) ?? [];
    siblings.push(row); byParent.set(row.parentId, siblings);
  }
  for (const siblings of byParent.values()) siblings.sort((a,b)=>a.sortOrder-b.sortOrder || a.id.localeCompare(b.id));
  const stack: {id?: string; rows: NavigationMenu[]; next: number}[] = [{rows:byParent.get(null) ?? [],next:0}];
  const active = new Set<string>(), result: MenuRow[] = [];
  while (stack.length) {
    const frame = stack[stack.length-1];
    if (frame.next === frame.rows.length) {stack.pop();if(frame.id)active.delete(frame.id);continue;}
    const row=frame.rows[frame.next++];
    if(active.has(row.id))continue;
    result.push({...row,level:stack.length-1});active.add(row.id);
    stack.push({id:row.id,rows:byParent.get(row.id) ?? [],next:0});
  }
  return result;
}

export function buildNavigationTree(rows: readonly NavigationMenu[]): NavigationTreeRow[] {
  const roots: NavigationTreeRow[]=[], parents: NavigationTreeRow[]=[];
  for(const {level,...row} of flattenNavigation(rows)) {
    const node: NavigationTreeRow={...row};
    parents.length=level;
    if(level===0)roots.push(node);
    else (parents[level-1].children ??= []).push(node);
    parents.push(node);
  }
  return roots;
}

/** Postorder filtering retains ancestors of matching descendants without mutating input. */
export function filterNavigationTree(nodes: readonly NavigationTreeRow[], matches: (node: NavigationTreeRow)=>boolean): NavigationTreeRow[] {
  type Frame={node?: NavigationTreeRow; rows: readonly NavigationTreeRow[]; next: number; result: NavigationTreeRow[]};
  const stack: Frame[]=[{rows:nodes,next:0,result:[]}];
  while(stack.length) {
    const frame=stack[stack.length-1];
    if(frame.next<frame.rows.length) {
      const node=frame.rows[frame.next++];
      stack.push({node,rows:node.children ?? [],next:0,result:[]});continue;
    }
    stack.pop();
    if(!frame.node)return frame.result;
    if(matches(frame.node)||frame.result.length)stack[stack.length-1].result.push({...frame.node,children:frame.result.length?frame.result:undefined});
  }
  return [];
}

export function collectExpandableKeys(nodes: readonly NavigationTreeRow[]): string[] {
  const pending=[...nodes].reverse(), result:string[]=[];
  while(pending.length) {
    const node=pending.pop()!;
    if(node.children?.length) {
      result.push(node.id);
      for(let index=node.children.length-1;index>=0;index--)pending.push(node.children[index]);
    }
  }
  return result;
}
