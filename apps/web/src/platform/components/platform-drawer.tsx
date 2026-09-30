"use client";

import {useLayoutEffect,useRef} from "react";
import {Drawer,type DrawerProps} from "antd";

/** Restore the launcher even when a drawer closes before its entry animation finishes. */
export function PlatformDrawer({open,...props}:DrawerProps){
 const launcher=useRef<HTMLElement|null>(null);
 const compositionEnded=useRef(0);
 useLayoutEffect(()=>{
  if(open){
   launcher.current=document.activeElement instanceof HTMLElement?document.activeElement:null;
   return;
  }
  const frame=requestAnimationFrame(()=>{
   const target=launcher.current;
   if(!target?.isConnected||!target.checkVisibility())return;
   // Nested drawers return to their parent; an unrelated open drawer keeps focus.
   if([...document.querySelectorAll('.ant-drawer-open')].some(drawer=>!drawer.contains(target)))return;
   target.focus({preventScroll:true});
  });
  return ()=>cancelAnimationFrame(frame);
 },[open]);
 useLayoutEffect(()=>{
  if(!open)return;
  const rememberComposition=()=>{compositionEnded.current=Date.now();};
  window.addEventListener("compositionend",rememberComposition);
  return ()=>window.removeEventListener("compositionend",rememberComposition);
 },[open]);
 return <Drawer {...props} open={open}
 onKeyDown={event=>{
  props.onKeyDown?.(event);
  // A closing portal can still occupy the library's global Escape stack. Handle
  // keys originating in this drawer locally; portal children keep their own turn.
  if(event.key!=="Escape"||!open||props.keyboard===false||event.defaultPrevented||event.isPropagationStopped()
   ||event.nativeEvent.isComposing||Date.now()-compositionEnded.current<200
   ||!(event.target instanceof Element)||!event.target.closest(".ant-drawer"))return;
  event.stopPropagation();props.onClose?.(event);
 }} styles={info=>{
  const styles=typeof props.styles==="function"?props.styles(info):props.styles;
  return {...styles,wrapper:{...styles?.wrapper,maxWidth:"100%"},footer:{...styles?.footer,display:"flex",justifyContent:"flex-end",flexShrink:0}};
 }} focusable={{...props.focusable,focusTriggerAfterClose:false}}/>;
}
