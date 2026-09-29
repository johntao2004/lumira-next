"use client";
import {createContext,useContext} from "react";
import {useEffect} from "react";
import {useQuery} from "@tanstack/react-query";
import {api} from "../api/client";
import {defaultBranding,type Branding} from "./model";
const Context=createContext(defaultBranding);
export const useBranding=()=>useContext(Context);
export function BrandingProvider({initial,children}:{initial:Branding;children:React.ReactNode}){
 const query=useQuery({
  queryKey:["public-branding"],
  queryFn:()=>api<Branding>("/public/branding"),
  initialData:initial,
  // A static SPA cannot inject request-specific branding into its HTML. Keep
  // the built-in value as resilient first paint, but always refresh it.
  initialDataUpdatedAt:0,
 });
 useEffect(()=>{
  const href=query.data.websiteFaviconUrl.trim();
  let link=document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if(!href){link?.remove();return;}
  if(!link){link=document.createElement("link");link.rel="icon";document.head.append(link);}
  link.href=href;
 },[query.data.websiteFaviconUrl]);
 return <Context.Provider value={query.data}>{children}</Context.Provider>;
}
export function BrandName(){const b=useBranding();return <>{b.websiteLogoUrl&&<img src={b.websiteLogoUrl} alt="" style={{height:28,maxWidth:100,objectFit:"contain",marginInlineEnd:8}}/>}{b.websiteName}</>;}
