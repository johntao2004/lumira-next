"use client";
import {Alert,Button,Popconfirm,Space,Typography} from "antd";
import {AsyncButton} from "../components/async-button";
import {usePreferences} from "../providers";
import type {useAccountDraft} from "./use-account-draft";
import {FeedbackBubble} from "../components/feedback-bubble";
import {formatDateTime} from "../i18n/format";
export function DraftActions({draftState,disabled=false,onSave,onRestore}:{draftState:ReturnType<typeof useAccountDraft>;disabled?:boolean;onSave:()=>unknown;onRestore:(payload:unknown)=>boolean}){
 const {t,locale}=usePreferences();const {draft,ready,busy,error,conflict,reload,save,discard}=draftState;
 const blocked=disabled||busy;
 return <Space orientation="vertical" className="mb-4 w-full" role="group" aria-label={t("drafts")}>
  <Typography.Text type="secondary">{t("draftHint")}</Typography.Text>
  {draft&&<Typography.Text type="secondary">{t("draftSavedAt")} {formatDateTime(draft.updatedAt,locale,{dateStyle:"short",timeStyle:"short"})}</Typography.Text>}
  {error&&conflict?<Alert type="warning" title={t("draftConflict")}/>:<FeedbackBubble kind="warning" content={error ? t(error) : undefined}/>} 
  <Space wrap>
   <AsyncButton label={t("saveDraft")} pending={busy} disabled={disabled||!ready||conflict} onClick={()=>void save(onSave()).catch(()=>{})}/>
   {draft&&<Popconfirm title={t("restoreDraftConfirm")} onConfirm={()=>{if(onRestore(draft.payload))draftState.markRestored();}} okText={t("restoreDraft")} cancelText={t("cancel")} disabled={blocked||conflict}>
    <Button disabled={blocked||conflict}>{t("restoreDraft")}</Button>
   </Popconfirm>}
   {draft&&<Popconfirm title={t("deleteDraft")} onConfirm={()=>discard().catch(()=>{})} okText={t("deleteDraft")} cancelText={t("cancel")} disabled={blocked||conflict}>
    <Button disabled={blocked||conflict}>{t("deleteDraft")}</Button>
   </Popconfirm>}
   <Button disabled={blocked||(!ready&&!error)} onClick={()=>void reload().catch(()=>{})}>{t("refreshDraft")}</Button>
  </Space>
 </Space>;
}
