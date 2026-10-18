"use client";
import { App, Button, Popconfirm } from "antd";
import type { ButtonProps } from "antd";
import { ApiError } from "../api/client";
import { messageKeyFor } from "../api/errors";
import { usePreferences } from "../providers";
import type { MessageKey } from "../i18n/messages";
import { useSubmitAction } from "../forms/use-submit-action";
import { deleteErrorKeys } from "./delete-errors";

/** Shared confirmation, pending state, and error feedback for versioned deletions. */
export function DeleteAction({name,onDelete,actionLabel,confirmation,disabled=false,conflictErrorKey="staleRecord",buttonType="link"}:{name:string;onDelete:()=>Promise<void>;actionLabel?:string;confirmation?:string;disabled?:boolean;conflictErrorKey?:MessageKey;buttonType?:ButtonProps["type"]}) {
  const {t}=usePreferences();
  const {message}=App.useApp();
  const action=useSubmitAction({
    mapError:error=>{
      const code=error instanceof ApiError?(error.message==="fileStateConflict"?conflictErrorKey:error.message):"unavailable";
      return messageKeyFor(code,deleteErrorKeys);
    },
    onError:key=>{void message.error(t(key));},
  });
  const pending=action.pending;
  // Keep confirmations above fixed table cells and outside their scroll clipping.
  return <Popconfirm getPopupContainer={()=>document.body} title={confirmation??t("deleteConfirm",{name})} okText={actionLabel??t("deleteRecord")} cancelText={t("cancel")} okButtonProps={{danger:true,loading:pending,"aria-label":actionLabel??t("deleteRecord"),"aria-busy":pending}} cancelButtonProps={{disabled:pending}} onConfirm={async()=>{
    if(action.isPending()||disabled)return;
    await action.run(onDelete);
  }} disabled={disabled}><Button type={buttonType} danger disabled={pending||disabled}>{actionLabel??t("deleteRecord")}</Button></Popconfirm>;
}
