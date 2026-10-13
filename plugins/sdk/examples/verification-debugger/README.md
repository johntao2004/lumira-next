# 验证调试器示例插件

验证调试器在本机提供 Aliyun、Tencent 形状的短信模拟 API，可把调用方传入的 6 位验证码放入当前登录人的私有收件箱，供本地函数或 HTTP 客户端检查“调用发送接口 → 收到成功协议响应 → 弹出验证码”的完整调试流程。模拟请求只接受合成号码 `+15555550123`、固定签名 `LumiraDebug` 和当前调试窗口生成的假凭据；不会调用云服务，也不接收真实号码、云密钥或任意目标 URL。

## 启用与会话

插件默认关闭。仅在隔离的本地或 QA 环境显式设置 `LUMIRA_SMS_DEBUG_ENABLED=true`（也可用配置属性 `lumira.sms.debug-enabled=true`），并在“模块管理”启用此插件后才会启动接收器。`prod` 和 `production` profile 会强制关闭。插件资源发布后才会尝试绑定随机端口；关闭开关、插件构造/刷新或健康检查都不会提前绑定。停用或卸载会关闭接收器并清除所有窗口和验证码。接收器仅绑定 `127.0.0.1`，拒绝带 `Origin` 的浏览器请求，因此浏览器应通过同源私有 API 调用。

调用 `POST /api/plugins/verification-debugger/sessions`（空 body 或 `{}`）会为当前登录人创建 10 分钟窗口。响应包含三种本地 endpoint 和仅此时展示的假凭据：

- `endpoint` 和 `bearerToken`：宿主 webhook 接收地址 `/send`。
- `providers.aliyun.endpoint`、`accessKeyId`、`accessKeySecret`：Aliyun 形状的本地模拟地址 `/aliyun`。
- `providers.tencent.endpoint`、`secretId`、`secretKey`：Tencent 形状的本地模拟地址 `/tencent`。

窗口消息只驻留内存 5 分钟，最多 50 条；每个窗口每分钟最多成功接收 5 次。新建或结束窗口、过期清理、关闭或卸载插件会擦除凭据和对应消息。真实验证码由调用方提供，插件不会替调用方生成业务验证码。`GET /api/plugins/verification-debugger/messages` 只返回当前登录人的窗口消息。

## 本地云短信 API

这两个直接接口使用云厂商常见的 JSON 请求字段和成功/失败响应形状，但认证是插件定义的本地适配器：`X-Lumira-Debug-Access-Key` 携带窗口假 ID，`Authorization: Bearer <假 secret>` 携带窗口假 secret。它们不是阿里云 ACS3 或腾讯云 TC3 签名，也不能与官方云 SDK/真实 endpoint 联调。可用 HTTP 客户端或本地函数直接请求下面的 endpoint；不带 `Origin` 请求头。

Aliyun 请求示例：

```http
POST <providers.aliyun.endpoint>
Content-Type: application/json
X-Lumira-Debug-Access-Key: <accessKeyId>
Authorization: Bearer <accessKeySecret>

{"PhoneNumbers":"+15555550123","SignName":"LumiraDebug","TemplateCode":"SMS_DEBUG","TemplateParam":"{\"code\":\"123456\"}"}
```

成功时返回 `Code: "OK"`、`Message: "OK"`、`RequestId` 和 `BizId`，并将 `123456` 作为当前窗口的私有消息保存。Tencent 请求示例：

```http
POST <providers.tencent.endpoint>
Content-Type: application/json
X-Lumira-Debug-Access-Key: <secretId>
Authorization: Bearer <secretKey>

{"PhoneNumberSet":["+15555550123"],"SmsSdkAppId":"1400000000","TemplateId":"1234","SignName":"LumiraDebug","TemplateParamSet":["123456"]}
```

成功时返回 `Response.SendStatusSet[0].Code: "Ok"`、`Message: "send success"` 和 `Response.RequestId`，并保存调用方传入的验证码。字段或号码不符合约定时返回相应云协议形状的参数错误；错误或失败请求不会写入收件箱。

前端的 `POST /api/plugins/verification-debugger/invoke` 接受 `{provider:"aliyun"|"tencent"|"webhook",body:{...}}`，由后端携带当前人的假凭据请求插件自己固定的 loopback endpoint，并返回 `{status,body}`。它不会接受浏览器提供的 URL 或认证头；此同源接口使用宿主 CSRF，并限制 4 KiB 请求体和 5 秒调用时间。`/send`、`/aliyun`、`/tencent` 直接接收器使用随机端口和有限并发，限制 8 KiB 请求体、拒绝任何 `Origin` 并禁用 CORS。

## 宿主 webhook 与协议 fixture

`/send` 兼容宿主现有 webhook 形状 `{to,sender,message}`，使用生成的 `bearerToken`。要把宿主真实验证码流程接到私有收件箱，需在隔离环境中手动将短信设置的 endpoint、token、sender、模板和市场配置为会话响应给出的值；消息模板固定为 `Lumira verification code: {code}`。身份服务仍负责真实验证码生成、加密入队和校验。本插件不会更改宿主短信配置、`SmsSender`、验证码校验或 `SecretStore`。完成演示后，在“模块管理”停用插件；如果宿主短信设置仍指向已关闭的 loopback endpoint，发送会失败关闭，不会假报成功或改用其他供应商。

接入 `/send` 后，可以分别验证登录验证码、注册验证码、短信找回密码、绑定手机号以及重新验证敏感操作（包括微信绑定/解绑和 MFA 管理）中的短信 OTP。以上业务仍调用原身份服务 OTP 生成、队列和校验流程；插件只接收匹配固定模板的合成号码消息，不替换业务服务。

`POST /api/plugins/verification-debugger/fixtures` 提供 `success`、`auth-failure`、`rate-limit`、`timeout`、`malformed` 五种示例，展示协议请求/响应形状，不执行真实调用。`success` 只有在当前人已开启窗口时，才会添加固定合成验证码 `135790` 以检查收件箱 UI；这不是身份服务产生的验证码。

协议字段参考官方文档：[阿里云 Java SDK 短信示例](https://help.aliyun.com/en/sms/developer-reference/using-java-openapi-example) 与 [腾讯云 SendSms API](https://intl.cloud.tencent.com/document/product/382/40536?lang=en)。官方字段/响应结构仅用于示例兼容，不表示真实云端发送或联调成功。

## 定向构建

使用 Node 24、pnpm 10.33.0 和 JDK 25，在仓库根目录运行：

```sh
node scripts/build-plugins.mjs --no-host sdk/examples/verification-debugger
```

此示例位于 SDK examples 目录，不属于默认生产插件包图。
