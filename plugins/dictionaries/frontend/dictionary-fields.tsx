"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {Input, Select, Switch} from "antd";
import {usePreferences} from "@/platform/providers";

export type DictionaryMetadata={code:string;name:string;structure:"FLAT"|"TREE";remark:string;enabled:boolean};
export const dictionaryDefaults={enabled:true,structure:"FLAT",remark:""} as const;

/** Shared field names and validation for creation, editing and file import. */
export function DictionaryFields({codeReadOnly=false,policyLocked=false,policyEnabledLocked=policyLocked}:{codeReadOnly?:boolean;policyLocked?:boolean;policyEnabledLocked?:boolean}) {
 const {t}=usePreferences();
 return <>
  <Form.Item name="code" label={t("dictionaryCode")} rules={[{required:true,pattern:/^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}$/,message:t("invalidDictionary")}]}><Input readOnly={codeReadOnly} maxLength={100}/></Form.Item>
  <Form.Item name="name" label={t("name")} rules={[{required:true,whitespace:true,message:t("nameRequired")},{max:200,message:t("nameTooLong")}]}><Input maxLength={200}/></Form.Item>
  <Form.Item name="structure" label={t("dictionaryStructure")} rules={[{required:true}]}><Select disabled={policyLocked} options={[{value:"FLAT",label:t("flatDictionary")},{value:"TREE",label:t("treeDictionary")}]} /></Form.Item>
  <Form.Item name="remark" label={t("remark")}><Input.TextArea maxLength={1000} showCount/></Form.Item>
  <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch disabled={policyEnabledLocked}/></Form.Item>
 </>;
}
