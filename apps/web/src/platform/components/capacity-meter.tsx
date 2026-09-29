"use client";
import {Progress,Typography} from "antd";
/** Shared quota display; amounts remain visible even above a reduced limit. */
export function CapacityMeter({label,used,limit,format}:{label:string;used:number;limit:number;format:(value:number)=>string}){
 const percent=limit>0?Math.min(100,Math.max(0,used/limit*100)):used>0?100:0;
 return <section aria-label={label}>
  <Typography.Text strong>{label}</Typography.Text>
  <div><Typography.Text>{format(used)} / {format(limit)}</Typography.Text></div>
  <Progress percent={percent} showInfo={false} status={used>=limit?"exception":"normal"}/>
 </section>;
}
