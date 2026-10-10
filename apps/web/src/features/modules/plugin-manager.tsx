import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, App, Button, Input, Radio, Space, Typography } from 'antd';
import { api, mutate, upload } from '@/platform/api/client';
import { usePreferences } from '@/platform/providers';
import { PlatformDrawer } from '@/platform/components/platform-drawer';
import type { PluginView } from '@/platform/modules/plugin-runtime';
export type PluginPlan={moduleId:string;kind:string;revision:string;install:string[];reuse:string[];enable:string[];remove:string[];blockers:string[];dataMode:'PRESERVE'|'DELETE';dataDeletionSupported:boolean;data:{label:string;count:number}[]};
type Operation={id:string;status:string;step:string;errorCode?:string};
export function usePluginManager(canWrite:boolean){
 const {t}=usePreferences();const {message,modal}=App.useApp();const client=useQueryClient();const file=useRef<HTMLInputElement>(null);
 const [confirmation,setConfirmation]=useState('');
 const [plan,setPlan]=useState<PluginPlan|null>(null);const [operation,setOperation]=useState<Operation|null>(null);const [pending,setPending]=useState(false);
 const query=useQuery({queryKey:['plugins'],queryFn:({signal})=>api<PluginView[]>('/platform/modules/plugins',{signal})});
 const lifecycleChangesAllowed=Boolean(query.data?.length&&query.data.every(plugin=>plugin.lifecycleChangesAllowed));
 const lifecycleBlockReason=query.data?.find(plugin=>!plugin.lifecycleChangesAllowed)?.lifecycleBlockReason??null;
 const refresh=async()=>{await Promise.all([client.invalidateQueries({queryKey:['plugins']}),client.invalidateQueries({queryKey:['modules']}),client.invalidateQueries({queryKey:['session']}),client.invalidateQueries({queryKey:['navigation-runtime']}),client.invalidateQueries({queryKey:['plugin-frontend']})]);window.dispatchEvent(new Event('lumira:modules-changed'));};
 const prepare=async(id:string,kind:'install'|'uninstall',dataMode:'PRESERVE'|'DELETE'='PRESERVE')=>{setPending(true);setOperation(null);setConfirmation('');try{setPlan(await mutate<PluginPlan>(`/platform/modules/plugins/${encodeURIComponent(id)}/${kind}-plan${kind==='uninstall'?`?dataMode=${dataMode}`:''}`,'POST'));}catch(e){void message.error(t(e instanceof Error?e.message:'unavailable'));}finally{setPending(false);}};
 const execute=async()=>{if(!plan||pending||!canWrite)return;setPending(true);try{
  let op=await mutate<Operation>(`/platform/modules/plugins/${encodeURIComponent(plan.moduleId)}/${plan.kind==='INSTALL'?'install':'uninstall'}`,'POST',{revision:plan.revision,dataMode:plan.dataMode,confirmation:plan.dataMode==='DELETE'?confirmation:undefined});setOperation(op);
  while(op.status==='QUEUED'||op.status==='RUNNING'){await new Promise(resolve=>setTimeout(resolve,700));op=await api<Operation>(`/platform/modules/plugins/operations/${op.id}`);setOperation(op);}
  await refresh();if(op.status==='SUCCEEDED'){void message.success(t('settingsSaved'));setPlan(null);}else void message.error(t(op.errorCode??'pluginStartFailed'));
 }catch(e){void message.error(t(e instanceof Error?e.message:'unavailable'));}finally{setPending(false);}};
 const requestExecute=()=>{if(!plan)return;if(plan.dataMode==='DELETE'){modal.confirm({title:t('pluginDeleteDataConfirm'),content:t('pluginDeleteDataWarning'),okText:t('pluginDeleteData'),okButtonProps:{danger:true},cancelText:t('cancel'),onOk:()=>execute()});return;}if(plan.kind==='UNINSTALL'){modal.confirm({title:t('uninstallPlugin'),content:<Space orientation="vertical"><Typography.Text>{names([plan.moduleId])}</Typography.Text><Typography.Text>{t('pluginKeepData')}</Typography.Text><Typography.Text type="secondary">{t('pluginDataRetained')}</Typography.Text></Space>,okText:t('uninstallPlugin'),cancelText:t('cancel'),onOk:()=>execute()});return;}void execute();};
 const controls=<><input ref={file} type="file" accept=".lumira-plugin.zip,.zip" hidden aria-label={t('importPlugin')} disabled={!lifecycleChangesAllowed} onChange={async event=>{const picked=event.target.files?.[0];event.target.value='';if(!picked)return;setPending(true);try{await upload('/platform/modules/plugins/import',picked);await refresh();void message.success(t('pluginImported'));}catch(e){void message.error(t(e instanceof Error?e.message:'unavailable'));}finally{setPending(false);}}}/>{canWrite&&<Button loading={pending} disabled={!lifecycleChangesAllowed} onClick={()=>file.current?.click()}>{t('importPlugin')}</Button>}</>;
 const names=(ids:string[])=>ids.map(id=>query.data?.find(p=>p.id===id)?.name??id).join('、')||'—';
 const drawer=<PlatformDrawer open={!!plan} title={t(plan?.kind==='INSTALL'?'installPlugin':'uninstallPlugin')} onClose={()=>{if(!pending)setPlan(null);}} footer={<Button type="primary" loading={pending} danger={plan?.dataMode==='DELETE'} disabled={pending||!canWrite||!!plan?.blockers.length||plan?.dataMode==='DELETE'&&confirmation!==plan.moduleId} onClick={requestExecute}>{t('confirm')}</Button>}>
  {plan&&<Space orientation="vertical" className="w-full" size="middle">
   <Typography.Text strong>{names([plan.moduleId])}</Typography.Text>
   {plan.blockers.map(reason=><Alert key={reason} type="error" showIcon title={t(reason.split(':')[0])} description={reason.includes(':')?names(reason.split(':')[1].split(',')):undefined}/>)}
   <Space orientation="vertical">{(plan.kind==='INSTALL'?[['pluginWillInstall',plan.install],['pluginWillReuse',plan.reuse],['pluginWillEnable',plan.enable]]:[['pluginWillRemove',plan.remove]]).map(([label,ids])=><Typography.Text key={label as string}>{t(label as string)}：{names(ids as string[])}</Typography.Text>)}</Space>
   {plan.kind==='UNINSTALL'&&<>
    <Typography.Text strong>{t('pluginUninstallData')}</Typography.Text>
    <Radio.Group value={plan.dataMode} disabled={pending} onChange={event=>void prepare(plan.moduleId,'uninstall',event.target.value)}>
     <Space orientation="vertical"><Radio value="PRESERVE">{t('pluginKeepData')}</Radio><Radio value="DELETE" disabled={!plan.dataDeletionSupported}>{t('pluginDeleteData')}</Radio></Space>
    </Radio.Group>
    {!plan.dataDeletionSupported&&<Typography.Text type="secondary">{t('pluginDataDeletionUnsupported')}</Typography.Text>}
    {plan.dataMode==='PRESERVE'?<Typography.Text type="secondary">{t('pluginDataRetained')}</Typography.Text>:<>
     <Alert type="warning" showIcon title={t('pluginDeleteDataWarning')}/>
     {plan.data.map((item,index)=><Typography.Text key={index}>{t(item.label)}：{item.count}</Typography.Text>)}
     <Typography.Text>{t('pluginDeleteDataHint',{id:plan.moduleId})}</Typography.Text>
     <Input aria-label={t('pluginDeleteDataHint',{id:plan.moduleId})} value={confirmation} disabled={pending} onChange={event=>setConfirmation(event.target.value)} autoComplete="off"/>
    </>}
   </>}
   {operation&&<Alert type={operation.status==='FAILED'?'error':'info'} title={t(operation.status==='FAILED'?operation.errorCode??'pluginStartFailed':'pluginProcessing')} description={operation.status==='FAILED'?operation.step:names([plan.moduleId])}/>}
  </Space>}
 </PlatformDrawer>;
 return {query,controls,drawer,prepare,pending,lifecycleChangesAllowed,lifecycleBlockReason};
}
