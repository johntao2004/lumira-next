/**
 * 将服务端插件清单接入共享加载器和 SDK，同步查询及本地化刷新，并在最后一个消费者退出时释放资源。
 * Connect the server plugin catalog to the shared loader and SDK, refresh queries and localization, and release resources when the final consumer leaves.
 */
import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, getAuthorizationContext } from '../api/client';
import { pluginSdkModules, type PluginSdkModules } from './plugin-sdk';
import { accountAvatarKey } from '../api/account-avatar-contract';
import { registerPluginPages } from './page-store';
import type { FrontendModule } from './types';
export type PluginView = { id:string; name:string; version:string; digest:string; installed:boolean; enabled:boolean; explicitInstall:boolean; status:'UNINSTALLED'|'RUNNING'|'STOPPED'|'FAILED'; lifecycleChangesAllowed:boolean; lifecycleBlockReason?:string|null; manifest:{ routes: readonly {id:string}[]; files:Record<string,string> } };
declare global {
  interface Window {
    __lumiraPluginSdk?: PluginSdkModules;
    __lumiraRegisterPlugin?: (id: string, bundle: Record<string, FrontendModule>) => void;
    LumiraPluginBundle?: unknown;
  }
}
let runtimeObservers=0;
let flushQueued=false;
let currentNotify:(()=>void)|null=null;
const pending=new Map<string,{version:string;digest:string;revision:string|null|undefined;script:HTMLScriptElement;resolve:(module:FrontendModule)=>void;reject:(error:Error)=>void}>();
const loaded=new Map<string,{digest:string;revision:string|null|undefined;remove:()=>void;script:HTMLScriptElement;styles:HTMLLinkElement[]}>();
function scheduleLoadedFlush(client:ReturnType<typeof useQueryClient>,fallbackNotify:()=>void){
 if(!currentNotify)currentNotify=fallbackNotify;
 if(flushQueued)return;
 flushQueued=true;
 void Promise.resolve().then(()=>{
  flushQueued=false;
  void client.invalidateQueries({queryKey:['modules']});
  void client.invalidateQueries({queryKey:['plugins']});
  void client.invalidateQueries({queryKey:['navigation-runtime']});
  void client.invalidateQueries({queryKey:accountAvatarKey});
  window.dispatchEvent(new Event('lumira:localization-changed'));
  currentNotify?.();
 });
}
function isFixedPluginGraph(plugins:PluginView[]|undefined):boolean{
 return Boolean(plugins&&plugins.length>0&&plugins.every(p=>!p.lifecycleChangesAllowed&&p.lifecycleBlockReason==='pluginFixedGraph'));
}
function assetUrl(digest:string,file:string){const url=new URL(`/api/platform/modules/plugins/assets/${digest}/${file}`,window.location.origin);const revision=getAuthorizationContext()?.revision;if(!revision)throw new Error('authorizationContextUnavailable');url.searchParams.set('authorizationContextRevision',revision);return url.toString();}
function loadPlugin(plugin:PluginView):Promise<FrontendModule>{
 const w=window;w.__lumiraPluginSdk=pluginSdkModules;
 w.__lumiraRegisterPlugin=(id,bundle)=>{const waiter=pending.get(id);if(!waiter||document.currentScript!==waiter.script){w.LumiraPluginBundle=undefined;return;}
  const module=Object.values(bundle).find(m=>m?.id===id);
  if(!module||module.version!==waiter.version){pending.delete(id);waiter.reject(new Error('pluginFrontendIncompatible'));}else waiter.resolve(module);
  w.LumiraPluginBundle=undefined;
 };
 return new Promise((resolve,reject)=>{const script=document.createElement('script');script.src=assetUrl(plugin.digest,'plugin.js');const revision=getAuthorizationContext()?.revision;pending.set(plugin.id,{version:plugin.version,digest:plugin.digest,revision,script,resolve,reject});
  script.onload=()=>{if(pending.get(plugin.id)?.script===script)fail(new Error('pluginFrontendMissing'));};
  script.onerror=()=>fail(new Error('pluginFrontendLoadFailed'));
  // 仅在注册动作已兑现 Promise 后才移除 pending 项。
  // Remove the pending entry only after registration has resolved the promise.
  const styles=Object.keys(plugin.manifest.files).filter(p=>p.startsWith('frontend/')&&p.endsWith('.css')).map(p=>{const link=document.createElement('link');link.rel='stylesheet';link.href=assetUrl(plugin.digest,p.slice('frontend/'.length));document.head.appendChild(link);return link;});
  const timer=setTimeout(()=>fail(new Error('pluginFrontendLoadFailed')),15000);
  const fail=(error:Error)=>{clearTimeout(timer);if(pending.get(plugin.id)?.script===script)pending.delete(plugin.id);script.remove();styles.forEach(s=>s.remove());reject(error);};
  const waiter=pending.get(plugin.id)!;waiter.reject=fail;waiter.resolve=module=>{if(pending.get(plugin.id)!==waiter||getAuthorizationContext()?.revision!==revision){fail(new Error('pluginFrontendCancelled'));return;}clearTimeout(timer);pending.delete(plugin.id);try{if(module.routes.length!==plugin.manifest.routes.length||module.routes.some(r=>!plugin.manifest.routes.some(expected=>expected.id===r.id)))throw new Error('pluginFrontendIncompatible');loaded.set(plugin.id,{digest:plugin.digest,revision,remove:registerPluginPages(module),script,styles});resolve(module);}catch(error){script.remove();styles.forEach(s=>s.remove());reject(error);}};
  document.head.appendChild(script);
 });
}
export function usePluginRuntime(){
 const client=useQueryClient();const [,render]=useState(0);const authorizationRevision=getAuthorizationContext()?.revision;
 const query=useQuery({queryKey:['plugin-frontend'],queryFn:({signal})=>api<PluginView[]>('/platform/modules/plugins/frontend',{signal}),refetchInterval:q=>isFixedPluginGraph(q.state.data)?false:2500,refetchIntervalInBackground:false});
 useEffect(()=>{let cancelled=false;
  const notify=()=>{if(!cancelled)render(n=>n+1);};
  currentNotify=notify;
  const active=query.data?new Map(query.data.map(p=>[p.id,p])):undefined;
  for(const [id,item] of pending)if(item.revision!==authorizationRevision||active&&active.get(id)?.digest!==item.digest)item.reject(new Error('pluginFrontendCancelled'));
  for(const [id,item] of loaded)if(item.revision!==authorizationRevision||active&&active.get(id)?.digest!==item.digest){item.remove();item.script.remove();item.styles.forEach(s=>s.remove());loaded.delete(id);client.removeQueries({predicate:q=>q.queryKey.some(k=>typeof k==='string'&&(k.startsWith(id)||id==='sensitive-words'&&k.startsWith('sensitive')||id==='localization'&&k.includes('localization')))});render(n=>n+1);void client.invalidateQueries({queryKey:['modules']});void client.invalidateQueries({queryKey:['plugins']});void client.invalidateQueries({queryKey:['session']});void client.invalidateQueries({queryKey:['navigation-runtime']});void client.invalidateQueries({queryKey:accountAvatarKey});window.dispatchEvent(new Event('lumira:localization-changed'));}
  for(const plugin of query.data??[])if(!loaded.has(plugin.id)&&!pending.has(plugin.id))void loadPlugin(plugin).then(()=>{scheduleLoadedFlush(client,notify);}).catch(error=>{console.error('Plugin frontend failed',plugin.id,error);});
  return ()=>{cancelled=true;if(currentNotify===notify)currentNotify=null;};
 },[query.data,query.dataUpdatedAt,client,authorizationRevision]);
 useEffect(()=>{const refresh=()=>{void query.refetch();void client.invalidateQueries({queryKey:['modules']});void client.invalidateQueries({queryKey:['plugins']});void client.invalidateQueries({queryKey:['session']});void client.invalidateQueries({queryKey:['navigation-runtime']});window.dispatchEvent(new Event('lumira:localization-changed'));};window.addEventListener('lumira:modules-changed',refresh);return()=>window.removeEventListener('lumira:modules-changed',refresh);},[client,query.refetch]);
 useEffect(()=>{runtimeObservers++;return()=>{runtimeObservers--;if(runtimeObservers===0){for(const item of pending.values())item.reject(new Error('pluginFrontendCancelled'));for(const item of loaded.values()){item.remove();item.script.remove();item.styles.forEach(s=>s.remove());}loaded.clear();}};},[]);
 return query;
}
