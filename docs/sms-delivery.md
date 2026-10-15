# 短信投递与供应商配置

管理页 `/dashboard/sms` 采用版本化保存，只保留“保存”操作，不提供配置历史、回滚或手动重新载入入口；写入要求全局 `platform:settings:update`。关闭“启用短信配置”时，下方字段和密钥只读，已有值保留；打开后可编辑。首次配置不预选厂家，先显示“请选择短信服务商”；仅提供真实云短信厂家。切换供应商会载入对应字段与默认值，清空草稿密钥，不会自动保存或发送。

## 供应商协议

| 供应商 | 国内与国际能力 | 协议与自动值 | 需要管理员提供 |
| --- | --- | --- | --- |
| 腾讯云 | 中国大陆、国际及港澳台模板短信 | SendSms 2021-01-11、TC3-HMAC-SHA256；固定 `sms.tencentcloudapi.com`；默认 `ap-guangzhou`，可选北京、南京 | SecretId/SecretKey、SmsSdkAppId、审核模板 ID、国内签名，国际 Sender ID（按目的地要求） |
| 阿里云中国站 | 国内与国际模板短信（需开通对应业务） | SendSms 2017-05-25、ACS3-HMAC-SHA256；固定 `dysmsapi.aliyuncs.com` | AccessKey ID/Secret、签名、TemplateCode |
| 阿里云国际站 | 中国大陆使用 SendMessageWithTemplate；海外使用 SendMessageToGlobe | 2018-05-01、HMAC-SHA1 RPC；固定新加坡端点 `dysmsapi.ap-southeast-1.aliyuncs.com` | 国际站 AccessKey、国内签名/模板或海外 Sender ID/正文 |
| 华为云 | 国内与国际模板短信 | `/sms/batchSendSms/v1`、WSSE；预填北京四/新加坡 APP 接入地址 | APP_Key/APP_Secret、实际 APP 地址、签名通道/国际通道、审核模板 ID；国内通用模板另填签名 |
| Twilio | 按账号开通的目的国家发送 | Messages API、Basic Auth；US1 或 IE1 地域端点；自动拼接 Account SID 路径 | Account SID、Auth Token 或 API Key SID/Secret、发送号码/Sender ID/Messaging Service SID |


腾讯云中国站的国际短信仍使用上述三个 API Region；“国际”不意味着将 Region 换成新加坡。华为云预填地址只作起点，必须核对控制台 APP 接入地址。Twilio IE1 要使用对应地域凭据。实际国家、Sender ID、资质及套餐范围由供应商账号决定。

表单自动生成 API 地址、版本、请求时间、随机 nonce、请求签名、验证码和有效分钟数。不能生成供应商账户密钥、审核签名、已获批模板 ID 或购买的发送号码。

云模板参数中，腾讯云/华为云使用 JSON 字符串数组，阿里云使用 JSON 字符串对象；默认 `["{code}"]` 或 `{"code":"{code}"}`，可增加 `{minutes}`。参数顺序/变量名必须与审核模板一致。Webhook、Twilio 和阿里云国际站海外正文使用文本模板。验证码保留前导零，禁止手机号变量和任意未知模板变量。选择中国大陆模式只接受大陆手机号码，国际模式接受其他 E.164 目的地；Webhook/Twilio 按其网关/账号范围处理。

## 官方依据

- 腾讯云：[SendSms](https://cloud.tencent.com/document/api/382/55981)、[公共参数及地域](https://cloud.tencent.com/document/api/382/52071)、[TC3 签名](https://cloud.tencent.com/document/api/382/52072)。
- 阿里云中国站：[SendSms](https://help.aliyun.com/zh/sms/developer-reference/api-dysmsapi-2017-05-25-sendsms)、[V3 请求与签名](https://help.aliyun.com/zh/sdk/product-overview/v3-request-structure-and-signature)。
- 阿里云国际站：[国内模板接口](https://www.alibabacloud.com/help/en/sms/developer-reference/api-dysmsapi-2018-05-01-sendmessagewithtemplate)、[海外接口](https://www.alibabacloud.com/help/en/sms/developer-reference/api-dysmsapi-2018-05-01-sendmessagetoglobe)、[公共参数](https://www.alibabacloud.com/help/en/sms/common-parameters)、[RPC 签名](https://www.alibabacloud.com/help/en/sms/signature-method)。
- 华为云：[发送接口](https://support.huaweicloud.com/intl/zh-cn/api-msgsms/sms_05_0001.html)、[Node.js 签名示例](https://support.huaweicloud.com/intl/en-us/devg-msgsms/sms_04_0008.html)。
- Twilio：[Messages API](https://www.twilio.com/docs/messaging/api/message-resource)、[地域 API](https://www.twilio.com/docs/messaging/api)。

## 凭据与网络边界

`SmsSettings` 仅保存非敏感账号标识和模板，凭据进入加密 `SecretStore`，不随当前值或历史返回。页面直接输入密钥，空白保留已保存密钥，有输入时可用小眼睛查看/隐藏；切换供应商、端点或账号必须输入新密钥，避免把旧密钥发给另一账户。启用前必须具备部署加密密钥与供应商凭据。配置结构兼容旧 Webhook JSON；短信业务拒绝结果约束直接包含在全局初始化 SQL 中。

云接口限定官方 HTTPS 域名和端点；Webhook 允许公共 HTTPS 或开发回环 HTTP。禁止 URL userinfo/query/fragment，连接时验证实际 DNS 地址并固定到公共地址，启用标准 TLS 校验、禁止重定向、限制响应大小。凭据不在日志中输出；国际站 RPC Signature 按官方要求在 query 中，因此不得另加记录完整 URL 的日志。

## 队列与状态

`platform_sms_delivery` 保存加密消息、过期时间、尝试次数、租约与令牌。验证码任务额外保存结构化 code、有效秒数及配置指纹。供应商、账号、模板或目的范围变更后，旧任务终止，避免使用新模板重解释旧内容。Secret 轮换和启停不改变内容指纹。

worker 通过 `FOR UPDATE SKIP LOCKED` 领取任务，数据库事务不包住网络调用；发送和完成都验证租约令牌，最多四个并发槽、五次尝试。

- `PENDING`：等待发送或确认未接收的连接/限流错误退避重试。
- `LEASED` / `SENDING`：worker 正在处理；发送中失去租约转 `UNCERTAIN`。
- `ACCEPTED`：响应中的供应商业务状态确认接受（Webhook 为 2xx），不等同于手机收到短信。
- `FAILED`：认证失败、业务拒绝、配置变更或耗尽重试，不自动重发。
- `UNCERTAIN`：超时、断连、异常响应或服务端错误可能已经接受短信，停止自动重发，防止重复投递。
- `EXPIRED`：超过生命周期。

云接口 HTTP 200 仍需校验业务状态：腾讯云逐条 Code=Ok、阿里云 Code/ResponseCode=OK、华为云整体及逐条状态均为 000000；Twilio 需 201、消息 SID 与有效接受状态。连接未建立或明确限流可重试；读取超时和未知错误不自动重发。Webhook 保留历史响应分类兼容性。日志和管理列表只显示脱敏元数据，无供应商回执集成或自动重放。

## 历史 JSON Webhook 合约

Webhook 是自建网关接入方式，并非短信服务商；已从管理页选项及默认选择中移除。后台只保留旧配置与本地测试的兼容能力。旧的未配置 Webhook 在管理页读取时显示为未选择厂家，读取不会改写配置或历史。

`POST`、`Content-Type: application/json`、`Authorization: Bearer <credential>`，正文：

```json
{"to":"+8613800138000","sender":"Lumira","message":"Code 002569"}
```

仅本地回环 `/send` 支持现有开发测试入口；禁止使用该入口测试云端账号或真实收费号码。

## 验证边界

`SmsProviderProtocolTest` 用固定时间/nonce 与独立计算的签名向量检查各家请求；`SmsCloudGatewayTest` 检查业务拒绝、认证、限流、异常响应、超时及凭据清理；`SmsSettingsTest` 检查官方端点、动态配置、模板类型与旧 JSON。数据库测试验证结构化消息加密往返、终止状态、租约隔离和六位验证码一次性使用。

这些检查不向供应商发真实短信。生产上线还需使用实际账户、审核签名和模板分别完成目标国家的供应商接受及收件箱送达验收。
