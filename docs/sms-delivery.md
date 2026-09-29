# 短信投递与供应商配置

V47 将短信能力拆成配置、队列和传输三层，默认关闭且不伪造成功结果。管理页为 `/dashboard/sms`，所有写入要求 `platform:settings:write` 和全局范围；读取、历史和回滚沿用平台设置的权限与版本契约。

## 配置边界

`SmsSettings` 只包含启用状态、固定的 `webhook` provider、endpoint、sender、模板和 1–30 秒超时。外部 endpoint 必须使用 HTTPS；开发环境允许 `localhost`、`127.0.0.1` 或 `::1` 的回环 HTTP，禁止 query、fragment、userinfo 和控制字符。模板必须包含 `{code}`，不能读取或插入手机号变量。

供应商密钥通过 `SecretStore` 加密保存，API 只接受 `KEEP`、`REPLACE`、`CLEAR` 三种动作，响应只返回是否已配置，不返回密钥。启用短信前必须同时具备加密密钥和供应商凭据。密钥含控制字符或为空时按终止性认证失败处理，避免让 JDK HTTP 构造器异常或重复发送。

## 队列与状态

`platform_sms_delivery` 保存加密后的 `OutgoingSms`、过期时间、尝试次数、租约和 lease token。写入请求先完成数据库入队，worker 再通过 `FOR UPDATE SKIP LOCKED` 获取一条租约；发送前和发送后的更新都校验 token。数据库事务不包住供应商网络调用，避免长事务和连接占用。

状态含义如下：

- `PENDING`：等待首次发送或连接失败后的退避重试。
- `LEASED` / `SENDING`：已被 worker 占用；租约过期会恢复，发送中租约过期转为 `UNCERTAIN`。
- `ACCEPTED`：供应商返回 2xx。
- `FAILED`：认证失败、配置不可用或达到尝试上限，不自动重试。
- `UNCERTAIN`：请求可能已被供应商接收，必须人工确认后再决定补发。
- `EXPIRED`：超过消息生命周期。

worker 使用虚拟线程但有四个并发槽，单条消息最多五次尝试，连接失败按 60 秒起步、最多 960 秒退避。日志只记录脱敏状态和任务 ID，不记录手机号、验证码、正文或密钥。运行监控展示 pending/failed 数量，当前没有外部供应商回执或自动重放。

## JSON webhook 合约

请求为 `POST`，`Content-Type: application/json`，带 `Authorization: Bearer <credential>`，正文只包含：

```json
{"to":"+8613800138000","sender":"Lumira","message":"Code 123"}
```

401/403 和非法 header 凭据为认证失败；其他 4xx（408/429 除外）为结果不确定；5xx、连接异常和中断进入受控重试/恢复路径。测试使用本地回环 HTTP server，不向外部号码发送短信。

## 验收

`SmsSettingsTest`、`SmsGatewayTest`、`SmsDeliveryStoreTest`、`AccountPhoneStoreTest` 和 `scripts/account-phone-browser-smoke.mjs` 覆盖配置验证、密钥边界、加密队列、租约 fencing、结果分类、过期/失败上限、未配置供应商反馈和移动端入口。`SmsDeliveryQuery` 与 `scripts/sms-delivery-browser-smoke.mjs` 另外覆盖脱敏列表、状态筛选、任务 ID 搜索和失败刷新重试。最新本地 PostgreSQL `clean verify` 为 242 项通过；外部供应商真实送达、人工不确定结果处理和生产多实例故障切换仍待单独验收。
