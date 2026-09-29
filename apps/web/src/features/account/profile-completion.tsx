"use client";
import {Progress,Spin,Typography} from "antd";
import {useQuery} from "@tanstack/react-query";
import {api} from "@/platform/api/client";
import {QueryFailure} from "@/platform/components/query-failure";
import {usePreferences} from "@/platform/providers";
type Score={percent:number;groups:{key:string;label:string;percent:number;items:{key:string;label:string;completed:boolean}[]}[]};
export function ProfileCompletion({open}:{open:boolean}){
 const {t,locale}=usePreferences();const query=useQuery({queryKey:['profile-completion'],queryFn:({signal})=>api<{score:Score}>('/account/profile-completion',{signal}),enabled:open,staleTime:0});
 if(query.error)return <QueryFailure title={t('unavailable')} pending={query.isFetching} onRetry={()=>void query.refetch()}/>;
 if(!query.data)return <Spin/>;
 const score=query.data.score;const missing=score.groups.flatMap(group=>group.items.filter(item=>!item.completed));
 return <section className="mb-4" aria-label={t('profileCompletion')}><Typography.Text strong>{t('profileCompletion')}</Typography.Text><Progress percent={score.percent}/>
 {score.groups.length>1&&score.groups.map(group=><div key={group.key}><Typography.Text>{group.label}</Typography.Text><Progress size="small" percent={group.percent}/></div>)}
 {missing.length>0&&<Typography.Paragraph type="secondary">{t('profileIncomplete',{count:missing.length})}: {new Intl.ListFormat(locale).format(missing.map(item=>item.label))}</Typography.Paragraph>}
 </section>;
}
