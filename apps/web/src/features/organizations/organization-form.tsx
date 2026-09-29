"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { Input, Switch } from "antd";
import {usePreferences} from "@/platform/providers";
import {ResourceSelect} from "@/platform/components/resource-select";
export type Organization={id:string;parentId:string|null;code:string;name:string;enabled:boolean;version:number};
export function OrganizationForm({selected,parent,pending,canWrite,onFinish}:{selected:Organization|null;parent:string;pending:boolean;canWrite:boolean;onFinish:(values:Record<string,unknown>)=>Promise<void>}){
 const {t}=usePreferences();const [form]=Form.useForm();
 return <Form form={form} id="organization-editor" layout="vertical" disabled={pending||!canWrite} initialValues={selected??{enabled:true,parentId:parent&&parent!=="root"?parent:null}} onFinish={values=>{void onFinish(values);}}>
  <Form.Item name="code" label={t("organizationCode")} rules={[{required:true,pattern:/^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}$/,message:t("invalidOrganization")}]}><Input maxLength={100} disabled={!!selected||pending}/></Form.Item>
  <Form.Item name="name" label={t("name")} rules={[{required:true,whitespace:true,message:t("nameRequired")},{max:200,message:t("nameTooLong")}]}><Input maxLength={200}/></Form.Item>
  <Form.Item name="parentId" label={t("parentOrganization")} extra={t("rootOrganizationHint")}><ResourceSelect endpoint="/platform/organizations" excludeId={selected?.id} disabled={pending||!canWrite}/></Form.Item>
  <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch/></Form.Item>
 </Form>;
}
