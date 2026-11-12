"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { MarkdownEditor } from "@/platform/components/markdown-editor";
import {SettingsSection} from "@/platform/components/settings-section";
import {usePreferences} from "@/platform/providers";
import { useLocation } from "@/platform/routing/link";
export function AgreementsPage({canWrite}:{canWrite:boolean}){
 const {t}=usePreferences();const {pathname}=useLocation();const anchorEnabled=pathname==="/dashboard/agreements";return <SettingsSection section="agreements" canWrite={canWrite} showSecondaryActions={false}>
 <Form.Item name={["data","userAgreementMarkdown"]} label={t("userAgreement")}><MarkdownEditor anchor="user-agreement" anchorEnabled={anchorEnabled} disabled={!canWrite}/></Form.Item>
 <Form.Item name={["data","privacyAgreementMarkdown"]} label={t("privacyAgreement")}><MarkdownEditor anchor="privacy-agreement" anchorEnabled={anchorEnabled} disabled={!canWrite}/></Form.Item>
 </SettingsSection>;
}
