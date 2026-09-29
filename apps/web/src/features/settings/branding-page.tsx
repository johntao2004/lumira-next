"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {Input, InputNumber, Switch} from "antd";
import {SettingsSection} from "@/platform/components/settings-section";
import {usePreferences} from "@/platform/providers";
import type {MessageKey} from "@/platform/i18n/messages";

const textFields=[
 ["websiteName","websiteName",200,true],["websiteFaviconUrl","websiteFavicon",2000,false],
 ["websiteLogoUrl","websiteLogo",2000,false],["loginBackgroundUrl","loginBackground",2000,false],
 ["githubLinkUrl","githubLink",2000,false],["helpLinkUrl","helpLink",2000,false],
 ["companyName","companyName",200,false],["footerIcp","footerIcp",200,false],
 ["footerPoliceBeian","footerPoliceBeian",200,false],["footerCopyright","footerCopyright",1000,false],
] as const satisfies readonly (readonly [string,MessageKey,number,boolean])[];
export function BrandingPage({canWrite}:{canWrite:boolean}){
 const {t}=usePreferences();
 return <SettingsSection section="branding" canWrite={canWrite}>
  {textFields.map(([field,label,max,required])=><Form.Item key={field} name={["data",field]} label={t(label)} rules={required?[{required:true,whitespace:true,message:t("invalidBranding")}]:[]}><Input maxLength={max}/></Form.Item>)}
  <Form.Item name={["data","copyrightStartYear"]} label={t("copyrightStartYear")} rules={[{required:true}]}><InputNumber min={1900} max={9999} precision={0}/></Form.Item>
  <Form.Item name={["data","githubLinkEnabled"]} label={t("githubLinkEnabled")} valuePropName="checked"><Switch/></Form.Item>
  <Form.Item name={["data","helpLinkEnabled"]} label={t("helpLinkEnabled")} valuePropName="checked"><Switch/></Form.Item>
 </SettingsSection>;
}
