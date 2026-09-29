"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {Alert, Input, InputNumber, Select, Switch, Tag} from "antd";
import {SettingsSection,type Snapshot} from "@/platform/components/settings-section";
import {usePreferences} from "@/platform/providers";

const initialExtras={credentials:{action:"KEEP"}};
function Credentials({snapshot}:{snapshot:Snapshot<object>}){
 const {t}=usePreferences();const form=Form.useFormInstance();
 const action=Form.useWatch(["credentials","action"],form);
 return <>
  <p><Tag color={snapshot.passwordConfigured?"green":"default"}>{t(snapshot.passwordConfigured?"smtpConfigured":"smtpNotConfigured")}</Tag></p>
  {!snapshot.encryptionConfigured&&<Alert type="warning" title={t("secretKeyUnavailable")} className="mb-4"/>}
  <Form.Item name={["credentials","action"]} label={t("smtpCredentialAction")} extra={t("smtpCredentialHint")} rules={[{required:true}]}>
   <Select aria-label={t("smtpCredentialAction")} onChange={()=>form.setFieldValue(["credentials","value"],undefined)} options={[
    {value:"KEEP",label:t("smtpKeep")},{value:"REPLACE",label:t("smtpReplace"),disabled:!snapshot.encryptionConfigured},{value:"CLEAR",label:t("smtpClear")},
   ]}/>
  </Form.Item>
  {action==="REPLACE"&&<Form.Item name={["credentials","value"]} label={t("password")} preserve={false} rules={[{required:true,message:t("smtpPasswordRequired")}]}><Input.Password autoComplete="new-password" maxLength={4096}/></Form.Item>}
 </>;
}
export function SmtpPage({canWrite}:{canWrite:boolean}){
 const {t}=usePreferences();
 return <SettingsSection section="smtp" canWrite={canWrite} initialExtras={initialExtras} renderExtras={snapshot=><Credentials snapshot={snapshot}/>}> 
  <Alert type="info" showIcon title={t("smtpConfigOnly")} className="mb-4"/>
  <Form.Item name={["data","enabled"]} label={t("smtpEnabled")} valuePropName="checked"><Switch/></Form.Item>
  <Form.Item name={["data","host"]} label={t("smtpHost")}><Input maxLength={253} autoComplete="off"/></Form.Item>
  <Form.Item name={["data","port"]} label={t("smtpPort")} rules={[{required:true}]}><InputNumber min={1} max={65535} precision={0}/></Form.Item>
  <Form.Item name={["data","security"]} label={t("smtpSecurity")} rules={[{required:true}]}><Select options={[{value:"STARTTLS",label:"STARTTLS"},{value:"TLS",label:"TLS"}]}/></Form.Item>
  <Form.Item name={["data","authentication"]} label={t("smtpAuthentication")} valuePropName="checked"><Switch/></Form.Item>
  <Form.Item name={["data","username"]} label={t("username")}><Input maxLength={320} autoComplete="off"/></Form.Item>
  <Form.Item name={["data","fromAddress"]} label={t("smtpFromAddress")}><Input maxLength={254}/></Form.Item>
  <Form.Item name={["data","fromName"]} label={t("smtpFromName")}><Input maxLength={120}/></Form.Item>
 </SettingsSection>;
}
