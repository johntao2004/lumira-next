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
import { createPluginLoader } from './plugin-loader';
import type { FrontendModule } from './types';
export type PluginView = { id:string; name:string; version:string; digest:string; installed:boolean; enabled:boolean; explicitInstall:boolean; status:'UNINSTALLED'|'RUNNING'|'STOPPED'|'FAILED'; lifecycleChangesAllowed:boolean; lifecycleBlockReason?:string|null; manifest:{ routes: readonly {id:string}[]; files:Record<string,string> } };
declare global {
  interface Window {
    __lumiraPluginSdk?: PluginSdkModules;
    __lumiraRegisterPlugin?: (id: string, bundle: Record<string, FrontendModule>) => void;
    LumiraPluginBundle?: unknown;
  }
}
function assetUrl(digest:string,file:string){const url=new URL(`/api/platform/modules/plugins/assets/${digest}/${file}`,window.location.origin);const revision=getAuthorizationContext()?.revision;if(!revision)throw new Error('authorizationContextUnavailable');url.searchParams.set('authorizationContextRevision',revision);return url.toString();}
const clients = new Set<ReturnType<typeof useQueryClient>>();
const listeners = new Set<() => void>();
let consumers = 0;
let loader: ReturnType<typeof createPluginLoader> | undefined;
function runtime() {
 if (loader) return loader;
 loader = createPluginLoader({document, assetUrl, register: registerPluginPages, changed(id, removed) {
  for (const client of clients) {
   if (removed) client.removeQueries({predicate:q=>q.queryKey.some(k=>typeof k==='string'&&(k.startsWith(id)||id==='sensitive-words'&&k.startsWith('sensitive')||id==='localization'&&k.includes('localization')))});
   for (const key of [['modules'], ['plugins'], ['session'], ['navigation-runtime'], accountAvatarKey]) void client.invalidateQueries({queryKey:key});
  }
  for (const listener of listeners) listener();
  window.dispatchEvent(new Event('lumira:localization-changed'));
 }});
 window.__lumiraPluginSdk = pluginSdkModules;
 window.__lumiraRegisterPlugin = (id, bundle) => { loader?.register(id, bundle); window.LumiraPluginBundle = undefined; };
 return loader;
}
export function usePluginRuntime(){
 const client=useQueryClient();const [,render]=useState(0);
 const query=useQuery({queryKey:['plugin-frontend'],queryFn:({signal})=>api<PluginView[]>('/platform/modules/plugins/frontend',{signal}),refetchInterval:2500,refetchIntervalInBackground:true});
 useEffect(()=>{
  consumers++; clients.add(client); const listener=()=>render(n=>n+1); listeners.add(listener);
  return()=>{listeners.delete(listener); if(--consumers===0){loader?.dispose();loader=undefined;clients.clear();}};
 },[client]);
 useEffect(()=>{
  if(!query.data)return;
  for(const attempt of runtime().synchronize(query.data)) void attempt.promise.catch(error=>{
   if(error instanceof Error && error.message==='pluginFrontendCancelled')return;
   console.error('Plugin frontend failed',attempt.id,error);
  });
 },[query.data,client]);
 const {refetch}=query;
 useEffect(()=>{const refresh=()=>{void refetch();void client.invalidateQueries({queryKey:['modules']});void client.invalidateQueries({queryKey:['plugins']});void client.invalidateQueries({queryKey:['session']});void client.invalidateQueries({queryKey:['navigation-runtime']});window.dispatchEvent(new Event('lumira:localization-changed'));};window.addEventListener('lumira:modules-changed',refresh);return()=>window.removeEventListener('lumira:modules-changed',refresh);},[client,refetch]);
 return query;
}
