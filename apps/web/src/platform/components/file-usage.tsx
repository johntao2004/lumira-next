"use client";
import {ProCard} from "@ant-design/pro-components";
import {Spin,Typography} from "antd";
import {useQuery} from "@tanstack/react-query";
import {api} from "@/platform/api/client";
import {CapacityMeter} from "@/platform/components/capacity-meter";
import {QueryFailure} from "@/platform/components/query-failure";
import {usePreferences} from "@/platform/providers";
type Usage={chargedBytes:number;fileCount:number;maximumBytes:number;maximumFiles:number;pendingDeletingCount?:number;pendingDeletingBytes?:number};
export function FileUsage({refreshInterval=5_000,pendingDeletionRefreshInterval=3_000}:{refreshInterval?:number;pendingDeletionRefreshInterval?:number}){
 const {t,locale}=usePreferences();
 const query=useQuery({queryKey:["files","usage"],queryFn:({signal})=>api<Usage>("/account/files/usage",{signal}),staleTime:0,refetchInterval:query=>((query.state.data?.pendingDeletingCount??0)>0?pendingDeletionRefreshInterval:refreshInterval),refetchOnWindowFocus:true});
 const bytes=(value:number)=>{const unit=value>=1024**3?3:value>=1024**2?2:value>=1024?1:0;return new Intl.NumberFormat(locale,{maximumFractionDigits:2}).format(value/1024**unit)+" "+["B","KiB","MiB","GiB"][unit];};
 const count=(value:number)=>new Intl.NumberFormat(locale).format(value);
 return (
  <ProCard className="file-usage-card mb-4">
   <section aria-label={t("fileUsage")}>
    <Typography.Title className="file-usage-title" level={5}>{t("fileUsage")}</Typography.Title>
    {query.error&&<QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={()=>void query.refetch()}/>}
    {query.data?<div className="file-usage-meters">
     <CapacityMeter label={t("fileStorageUsage")} used={query.data.chargedBytes} limit={query.data.maximumBytes} format={bytes} unlimitedWhenZero unlimitedLabel={t("roleQuotaUnlimited")}/>
     <CapacityMeter label={t("fileCountUsage")} used={query.data.fileCount} limit={query.data.maximumFiles} format={count} unlimitedWhenZero unlimitedLabel={t("roleQuotaUnlimited")}/>
    </div>:!query.error&&<Spin/>}
    {query.data&&(query.data.pendingDeletingCount??0)>0&&<Typography.Paragraph className="file-usage-pending" type="secondary">{t("fileUsagePendingDeletion",{count:count(query.data.pendingDeletingCount??0),size:bytes(query.data.pendingDeletingBytes??0)})}</Typography.Paragraph>}
    <Typography.Paragraph className="file-usage-hint" type="secondary">{t("fileUsageHint")}</Typography.Paragraph>
   </section>
  </ProCard>
 );
}
