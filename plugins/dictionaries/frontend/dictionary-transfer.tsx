"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { App } from "antd";
import { ApiError, api } from "@/platform/api/client";
import { errorCode } from "@/platform/api/errors";
import { downloadJson } from "@/platform/files/download-json";
import { AsyncButton } from "@/platform/components/async-button";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";

export function errorKey(e:unknown):MessageKey {
  if(errorCode(e)==="sensitiveContent")return "sensitiveContent";
  if(e instanceof ApiError) {
    if(e.message==="duplicateRecord")return "duplicateDictionary";
    if(e.message==="dictionaryDatasetTooLarge")return "datasetTooLarge";
    if(e.status===400)return "invalidDataset";
    if(e.status===403)return "forbidden";
  }
  return e instanceof SyntaxError?"invalidDataset":"unavailable";
}
export function DictionaryExport({id}:{id:string}) {
  const {t}=usePreferences();const {message}=App.useApp();const {pending,run}=useSubmitAction({mapError:errorKey,onError:key=>{void message.error(t(key));}});
  return <AsyncButton type="link" pending={pending} label={t("exportDictionary")} onClick={()=>run(async()=>{downloadJson(`dictionary-${id}.json`,await api(`/platform/dictionaries/${id}/export`));})} />;
}
