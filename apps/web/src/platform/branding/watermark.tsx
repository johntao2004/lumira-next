"use client";
import {Watermark} from "antd";
import {useQuery} from "@tanstack/react-query";
import {api} from "../api/client";
export interface WatermarkSettings {
 enabled:boolean;mode:"TEXT"|"PERSONALIZED_TEXT"|"IMAGE";textLines:string[];personalizedTextLines:string[];imageUrl:string;
 fontColor:string;fontSize:number;fontWeight:string;rotate:number;gapX:number;gapY:number;offsetX:number;offsetY:number;zIndex:number;opacity:number;
}
type Profile={id:string;username:string;displayName:string;realName:string};
export function PlatformWatermark({children}:{children:React.ReactNode}){
 const query=useQuery({queryKey:["public-watermark"],queryFn:()=>api<WatermarkSettings>("/public/watermark")});
 const setting=query.data;
 const profile=useQuery({queryKey:["profile"],queryFn:()=>api<Profile>("/account/profile"),enabled:!!setting?.enabled&&setting.mode==="PERSONALIZED_TEXT"});
 const user=profile.data;
 const values:Record<string,string>=user?{username:user.username,nickname:user.displayName,realName:user.realName,userId:user.id}:{};
 const personalized=user?setting?.personalizedTextLines.map(line=>line.replace(/\{\{([A-Za-z][A-Za-z0-9_]*)\}\}/g,(_,name:string)=>values[name]??"")).filter(line=>line.trim()):[];
 const content=setting?.mode==="PERSONALIZED_TEXT"&&personalized?.length?personalized:setting?.textLines;
 return <>{children}{setting?.enabled&&<Watermark className="platform-watermark" image={setting.mode==="IMAGE"?setting.imageUrl:undefined} content={setting.mode!=="IMAGE"?content:undefined}
 font={{color:setting.fontColor,fontSize:setting.fontSize,fontWeight:setting.fontWeight==="normal"||setting.fontWeight==="bold"?setting.fontWeight:Number(setting.fontWeight)}}
 rotate={setting.rotate} gap={[setting.gapX,setting.gapY]} offset={[setting.offsetX,setting.offsetY]} zIndex={setting.zIndex}
 style={{position:"fixed",inset:0,zIndex:setting.zIndex,pointerEvents:"none",opacity:setting.opacity}}/>}</>;
}
