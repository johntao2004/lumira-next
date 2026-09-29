"use client";
import {useEffect,useRef,useState} from "react";
import {api,ApiError,mutate} from "../api/client";
import { messageKeyFor } from "../api/errors";
import type {MessageKey} from "../i18n/messages";
export interface AccountDraft {revision:string;payload:unknown;updatedAt:string}
const draftErrorKeys = ["draftTooLarge", "draftQuotaExceeded", "invalidDraft", "staleRecord"] as const satisfies readonly MessageKey[];
const errorKey=(error:unknown):MessageKey=>messageKeyFor(error,draftErrorKeys);
/** Mount once per editor/key. No global cache, silent restoration or blind overwrite retries. */
export function useAccountDraft(key:string|undefined){
 const [draft,setDraft]=useState<AccountDraft|null>(null),[ready,setReady]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState<MessageKey|null>(null),[conflict,setConflict]=useState(false);
 const alive=useRef(false),locked=useRef(false),revision=useRef<string|null>(null),consumed=useRef<string|null>(null);
 const path=key?`/account/drafts/${encodeURIComponent(key)}`:undefined;
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 useEffect(()=>{
  const abort=new AbortController();setReady(false);setDraft(null);revision.current=null;setError(null);setConflict(false);
  if(path)void api<AccountDraft|undefined>(path,{signal:abort.signal}).then(value=>{if(abort.signal.aborted)return;setDraft(value??null);revision.current=value?.revision??null;setReady(true);}).catch(e=>{if(!abort.signal.aborted)setError(errorKey(e));});
  return()=>abort.abort();
 },[path]);
 async function perform(action:()=>Promise<void>){
  if(!path||locked.current)return;locked.current=true;setBusy(true);setError(null);
  try{await action();}catch(e){if(alive.current){setError(errorKey(e));if(e instanceof ApiError&&e.message==="staleRecord")setConflict(true);}throw e;}
  finally{locked.current=false;if(alive.current)setBusy(false);}
 }
 const reload=()=>{const targetPath=path;if(!targetPath)return Promise.resolve();return perform(async()=>{const value=await api<AccountDraft|undefined>(targetPath);if(alive.current){revision.current=value?.revision??null;setDraft(value??null);setReady(true);setConflict(false);}});};
 const save=(payload:unknown)=>{const targetPath=path;if(!targetPath)return Promise.resolve();return perform(async()=>{
  if(!ready||conflict)throw new ApiError(409,"staleRecord");
  const value=await mutate<AccountDraft>(targetPath,"PUT",{revision:revision.current,payload});
  if(alive.current){revision.current=value.revision;consumed.current=value.revision;setDraft(value);}
 });};
 const discard=()=>{const targetPath=path;if(!targetPath)return Promise.resolve();return perform(async()=>{
  if(!ready||conflict)throw new ApiError(409,"staleRecord");
  if(revision.current)await mutate(`${targetPath}?revision=${encodeURIComponent(revision.current)}`,"DELETE");
  if(alive.current){revision.current=null;setDraft(null);}
 });};
 const markRestored=()=>{consumed.current=revision.current;};
 const clearConsumed=async()=>{const used=consumed.current;if(path&&used)await mutate(`${path}?revision=${encodeURIComponent(used)}`,"DELETE");consumed.current=null;};
 return {draft,ready,busy,error,conflict,reload,save,discard,markRestored,clearConsumed};
}
