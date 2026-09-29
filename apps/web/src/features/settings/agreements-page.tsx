"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {Input} from "antd";
import {SettingsSection} from "@/platform/components/settings-section";
import {usePreferences} from "@/platform/providers";
export function AgreementsPage({canWrite}:{canWrite:boolean}){
 const {t}=usePreferences();return <SettingsSection section="agreements" canWrite={canWrite}>
 <Form.Item name={["data","userAgreementMarkdown"]} label={t("userAgreement")}><Input.TextArea rows={14} maxLength={100000} showCount/></Form.Item>
 <Form.Item name={["data","privacyAgreementMarkdown"]} label={t("privacyAgreement")}><Input.TextArea rows={14} maxLength={100000} showCount/></Form.Item>
 </SettingsSection>;
}
