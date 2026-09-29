"use client";
import {App, Button} from "antd";
import {useEffect, useId, useRef} from "react";
import {usePreferences} from "../providers";
/** Keep existing content visible while surfacing recoverable failures as a bubble. */
export function QueryFailure({title,pending,onRetry}:{title:string;pending:boolean;onRetry:()=>void}){
 const {t}=usePreferences();
 const retryLabel=t("retry");
 const {notification}=App.useApp();
 const id=`query-failure-${useId()}`;
 const retry=useRef(onRetry);
 useEffect(()=>{retry.current=onRetry;},[onRetry]);
 useEffect(()=>{
  if(pending){notification.destroy(id);return;}
  notification.error({
   key:id,
   message:title,
   duration:0,
   btn:<Button size="small" onClick={()=>{notification.destroy(id);retry.current();}}>{retryLabel}</Button>,
  });
  return()=>notification.destroy(id);
 },[id,notification,pending,retryLabel,title]);
 return null;
}
