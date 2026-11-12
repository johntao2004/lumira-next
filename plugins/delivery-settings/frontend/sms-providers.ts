import type { MessageKey } from "@/platform/i18n/messages";

export type SmsProvider = "" | "webhook" | "tencent" | "aliyun" | "alibaba_intl" | "huawei" | "twilio";
export type SmsMarket = "domestic" | "international";
export type SmsData = {
  enabled: boolean; provider: SmsProvider; endpoint: string; sender: string; template: string;
  timeoutSeconds: number; market: SmsMarket; region: string; accessKeyId: string; appId: string;
  templateId: string; templateParams: string; senderId: string; signature: string;
};
export const smsProviders: { value: SmsProvider; label: MessageKey; documentation?: string }[] = [
  { value: "tencent", label: "smsProviderTencent", documentation: "https://cloud.tencent.com/document/api/382/55981" },
  { value: "aliyun", label: "smsProviderAliyun", documentation: "https://help.aliyun.com/zh/sms/developer-reference/api-dysmsapi-2017-05-25-sendsms" },
  { value: "huawei", label: "smsProviderHuawei", documentation: "https://support.huaweicloud.com/intl/zh-cn/api-msgsms/sms_05_0001.html" },
  { value: "alibaba_intl", label: "smsProviderAlibabaIntl", documentation: "https://www.alibabacloud.com/help/en/sms/list-of-operations-by-function" },
  { value: "twilio", label: "smsProviderTwilio", documentation: "https://www.twilio.com/docs/messaging/api/message-resource" },
];
export function smsIsSelectableProvider(provider: SmsProvider) {
  return smsProviders.some(item => item.value === provider);
}
export function smsProviderDefaults(provider: SmsProvider, market: SmsMarket = "domestic"): Omit<SmsData, "enabled"> {
  const defaults: Omit<SmsData, "enabled"> = {
    provider, market: provider === "twilio" ? "international" : market,
    endpoint: "", sender: "", template: "Lumira verification code: {code}", timeoutSeconds: 10,
    region: "", accessKeyId: "", appId: "", templateId: "", templateParams: "", senderId: "", signature: "",
  };
  if (provider === "tencent") Object.assign(defaults, { endpoint: "https://sms.tencentcloudapi.com", region: "ap-guangzhou", templateParams: '["{code}"]' });
  if (provider === "aliyun") Object.assign(defaults, { endpoint: "https://dysmsapi.aliyuncs.com", region: "cn-hangzhou", templateParams: '{"code":"{code}"}' });
  if (provider === "alibaba_intl") Object.assign(defaults, { endpoint: "https://dysmsapi.ap-southeast-1.aliyuncs.com", region: "ap-southeast-1", templateParams: '{"code":"{code}"}' });
  if (provider === "huawei") Object.assign(defaults, { endpoint: `https://smsapi.${market === "domestic" ? "cn-north-4" : "ap-southeast-1"}.myhuaweicloud.com/sms/batchSendSms/v1`, templateParams: '["{code}"]' });
  if (provider === "twilio") Object.assign(defaults, { endpoint: "https://api.twilio.com", region: "us1" });
  if (provider === "webhook") defaults.sender = "Lumira";
  return defaults;
}
export function smsUsesCloudTemplate(provider: SmsProvider, market: SmsMarket) {
  return provider === "tencent" || provider === "aliyun" || provider === "huawei" || provider === "alibaba_intl" && market === "domestic";
}
