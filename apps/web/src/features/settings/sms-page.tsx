/** 编辑短信供应商配置，保留用户自定端点和地区。
 * Edit SMS provider settings while preserving custom endpoints and regions. */
"use client";
import { useRef, useState } from "react";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { InfoCircleOutlined } from "@ant-design/icons";
import { Input, InputNumber, Select, Switch, Tooltip } from "antd";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { SettingsSecretField } from "@/platform/components/settings-secret-field";
import { SettingsSection, type Snapshot } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import { smsProviders, smsProviderDefaults, smsUsesCloudTemplate, smsIsSelectableProvider, type SmsData, type SmsProvider, type SmsMarket } from "./sms-providers";

const initialExtras = { credentials: { action: "KEEP" } };
function HelpLabel({ label, hint }: { label: string; hint: string }) {
  return <span className="settings-help-label"><span>{label}</span><Tooltip title={hint} placement="top" trigger={["hover", "focus"]}>
    <span className="settings-help-label-icon" tabIndex={0} role="img" aria-label={hint}><InfoCircleOutlined /></span>
  </Tooltip></span>;
}
function Credentials({ snapshot }: { snapshot: Snapshot<SmsData> }) {
  const { t } = usePreferences(); const form = Form.useFormInstance();
  const provider = Form.useWatch(["data", "provider"], form) ?? snapshot.data.provider;
  if (!smsIsSelectableProvider(provider)) return null;
  const label = provider === "tencent" ? "SecretKey" : provider === "huawei" ? "APP_Secret" : provider === "twilio" ? t("smsTwilioSecret") : provider === "webhook" ? t("smsAccessSecret") : "AccessKey Secret";
  return <SettingsSecretField label={label} encryptionConfigured={snapshot.encryptionConfigured === true}
    keepHint={snapshot.credentialConfigured ? t("smsSecretKeepHint") : undefined} />;
}
function SmsFields() {
  const { t } = usePreferences(); const form = Form.useFormInstance();
  const provider: SmsProvider = Form.useWatch(["data", "provider"], form) ?? form.getFieldValue(["data", "provider"]) ?? "";
  const market: SmsMarket = Form.useWatch(["data", "market"], form) ?? form.getFieldValue(["data", "market"]) ?? "domestic";
  const enabled = Form.useWatch(["data", "enabled"], form) ?? form.getFieldValue(["data", "enabled"]);
  const cloudTemplate = smsUsesCloudTemplate(provider, market);
  const documentation = smsProviders.find(item => item.value === provider)?.documentation;
  const required = [{ required: enabled === true }];
  const providerRules = [{ required: enabled === true, message: t("smsSelectProvider") }, { validator: async (_: unknown, value: SmsProvider) => {
    if (enabled === true && !smsIsSelectableProvider(value)) throw new Error(t("smsSelectProvider"));
  } }];
  const endpointEdited = useRef(false);
  const selectProvider = (next: SmsProvider) => {
    endpointEdited.current = false;
    form.setFieldsValue({ data: smsProviderDefaults(next, next === "alibaba_intl" || next === "twilio" ? "international" : "domestic"), credentials: { action: "KEEP", value: undefined } });
  };
  const selectMarket = (next: SmsMarket) => {
    // 市场联动只更新未手改的默认端点，地区选择保持原值。
    // Market changes update only untouched default endpoints and preserve selected regions.
    const endpoint = form.getFieldValue(["data", "endpoint"]);
    if (provider === "huawei" && !endpointEdited.current && (!endpoint || endpoint === smsProviderDefaults(provider, market).endpoint)) {
      form.setFieldValue(["data", "endpoint"], smsProviderDefaults(provider, next).endpoint);
    }
  };
  const senderLabel = provider === "huawei" ? t("smsChannel") : provider === "twilio" ? t("smsTwilioSender") : provider === "webhook" ? t("smsSender") : provider === "alibaba_intl" && market === "international" ? "Sender ID" : t("smsSignName");
  return <>
    <Form.Item name={["data", "enabled"]} label={t("smsEnabled")} valuePropName="checked"><Switch /></Form.Item>
    <SettingsEnabledControls>
      <Form.Item name={["data", "provider"]} label={t("smsProvider")} rules={providerRules}
        getValueProps={(value: SmsProvider) => ({ value: smsIsSelectableProvider(value) ? value : undefined })}
        extra={documentation ? <a href={documentation} target="_blank" rel="noreferrer">{t("smsOfficialDocs")}</a> : undefined}>
        <Select placeholder={t("smsSelectProvider")} onChange={selectProvider} options={smsProviders.map(item => ({ value: item.value, label: t(item.label) }))} />
      </Form.Item>
      {smsIsSelectableProvider(provider) && <>
      {provider !== "webhook" && provider !== "twilio" && <Form.Item name={["data", "market"]} label={t("smsMarket")} rules={required}>
        <Select onChange={selectMarket} options={[{ value: "domestic", label: t("smsDomestic") }, { value: "international", label: t("smsInternational") }]} />
      </Form.Item>}
      {provider === "tencent" && <Form.Item name={["data", "region"]} label={t("smsRegion")} rules={required}><Select options={["ap-guangzhou", "ap-beijing", "ap-nanjing"].map(value => ({ value, label: value }))} /></Form.Item>}
      {provider === "twilio" && <Form.Item name={["data", "region"]} label={t("smsRegion")} rules={required}>
        <Select onChange={(next: string) => form.setFieldValue(["data", "endpoint"], next === "ie1" ? "https://api.dublin.ie1.twilio.com" : "https://api.twilio.com")}
          options={[{ value: "us1", label: t("smsTwilioUS") }, { value: "ie1", label: t("smsTwilioIE") }]} />
      </Form.Item>}
      <Form.Item name={["data", "endpoint"]} label={provider === "huawei" ? t("smsHuaweiEndpoint") : t("smsServiceEndpoint")}
        rules={provider === "webhook" || provider === "huawei" ? required : undefined}>
        <Input maxLength={2000} autoComplete="off" onChange={() => { endpointEdited.current = true; }} readOnly={provider !== "webhook" && provider !== "huawei"} />
      </Form.Item>
      {provider !== "webhook" && <Form.Item name={["data", "accessKeyId"]}
        label={provider === "tencent" ? "SecretId" : provider === "huawei" ? "APP_Key" : provider === "twilio" ? t("smsTwilioApiKey") : "AccessKey ID"}
        rules={provider !== "twilio" ? required : undefined}><Input autoComplete="off" maxLength={128} /></Form.Item>}
      {(provider === "tencent" || provider === "twilio") && <Form.Item name={["data", "appId"]} label={provider === "tencent" ? "SmsSdkAppId" : "Account SID"} rules={required}><Input autoComplete="off" maxLength={64} /></Form.Item>}
      <Form.Item name={["data", "sender"]} label={senderLabel} rules={provider === "tencent" && market === "international" ? undefined : required}>
        <Input maxLength={64} autoComplete="off" />
      </Form.Item>
      {provider === "tencent" && market === "international" && <Form.Item name={["data", "senderId"]} label="Sender ID"><Input maxLength={64} /></Form.Item>}
      {provider === "huawei" && market === "domestic" && <Form.Item name={["data", "signature"]} label={<HelpLabel label={t("smsSignName")} hint={t("smsHuaweiSignatureHint")} />}><Input maxLength={64} /></Form.Item>}
      {cloudTemplate ? <>
        <Form.Item name={["data", "templateId"]} label={provider === "aliyun" || provider === "alibaba_intl" ? "TemplateCode" : "Template ID"} rules={required}><Input maxLength={64} autoComplete="off" /></Form.Item>
        <Form.Item name={["data", "templateParams"]} label={<HelpLabel label={t("smsTemplateParameters")} hint={t("smsParametersHint")} />} rules={required}>
          <Input.TextArea maxLength={2000} autoSize={{ minRows: 2, maxRows: 5 }} />
        </Form.Item>
      </> : <Form.Item name={["data", "template"]} label={<HelpLabel label={t("smsTemplate")} hint={t("smsTextTemplateHint")} />} rules={required}>
        <Input.TextArea maxLength={500} autoSize={{ minRows: 2, maxRows: 5 }} />
      </Form.Item>}
      <Form.Item name={["data", "timeoutSeconds"]} label={t("smsTimeout")} rules={[{ required: true, type: "integer", min: 1, max: 30, message: t("invalidSms") }]}><InputNumber min={1} max={30} precision={0} /></Form.Item>
      </>}
    </SettingsEnabledControls>
  </>;
}
export function SmsPage({ canWrite, onSaved }: { canWrite: boolean; onSaved?: () => void | Promise<void> }) {
  const [savedGeneration, setSavedGeneration] = useState(0);
  return <SettingsSection<SmsData> section="sms" formName="settings-sms" canWrite={canWrite} showSecondaryActions={false} initialExtras={initialExtras} onSaved={async () => { setSavedGeneration(value => value + 1); await onSaved?.(); }}
    renderExtras={snapshot => <Credentials snapshot={snapshot} />}><SmsFields key={savedGeneration} /></SettingsSection>;
}
