"use client";
import {Progress,Typography} from "antd";
/** Shared quota display; amounts remain visible even above a reduced limit. */
export function CapacityMeter({label,used,limit,format,unlimitedWhenZero=false,unlimitedLabel}:{label:string;used:number;limit:number;format:(value:number)=>string;unlimitedWhenZero?:boolean;unlimitedLabel?:string}){
 const unlimited = unlimitedWhenZero && limit === 0;
 const percent=unlimited?0:limit>0?Math.min(100,Math.max(0,used/limit*100)):used>0?100:0;
 const overLimit = !unlimited && (limit === 0 ? used > 0 : used >= limit);
 return <section aria-label={label}>
  <Typography.Text strong>{label}</Typography.Text>
  <div><Typography.Text>{format(used)} / {unlimited ? unlimitedLabel ?? format(limit) : format(limit)}</Typography.Text></div>
  <Progress percent={percent} showInfo={false} status={overLimit?"exception":"normal"}/>
 </section>;
}
