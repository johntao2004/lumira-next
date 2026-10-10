# 注册与验证码登录接口契约

本文冻结 P04/P06 前后端交换结构。实现和验收状态以交付证据为准；本文不表示端点已经可用。认证状态、策略合并和事务边界遵守 [统一设计](role-authentication.md)，注册规则遵守 [公开注册设计](public-registration.md)。

## 通用约定

方式枚举为 `EMAIL`、`SMS`；它们对应主认证方法 `EMAIL_OTP`、`SMS_OTP`。时间为 ISO 8601 UTC 字符串。所有响应禁止缓存，写入保留 CSRF。密码、验证码、proof 和 requestId 不写入 URL、草稿或浏览器持久存储。

验证码响应的时间由服务端计算，客户端只显示倒计时。队列接受使用 202，不表示实际送达；限流返回 429 和 `Retry-After`。不存在、停用和当前策略不允许的账号保持相同登录挑战响应，不能返回联系方式是否已绑定。

## 注册

`GET /api/auth/registration/config`：

```ts
type RegistrationConfig = {
  available: boolean;
  reason: 'REGISTRATION_CLOSED' | 'REGISTRATION_UNAVAILABLE' | 'MAINTENANCE' | null;
  channels: Array<'EMAIL' | 'SMS'>;
  policyVersion: number;
  passwordRules: {
    minLength: number;
    maxBytes: number;
    requireUppercase: boolean;
    requireLowercase: boolean;
    requireSpecialCharacter: boolean;
    allowConsecutiveCharacters: boolean;
  };
  codePolicy: { expiresInSeconds: number; cooldownSeconds: number };
  agreements: {
    version: number;
    userAgreementMarkdown: string;
    privacyAgreementMarkdown: string;
  };
};
```

暂停时 `channels` 为空；只返回公开原因，不返回角色、权限、密钥或内部就绪失败详情。协议版本沿用设置版本，设置发布与最终创建持有兼容的锁并保存不可变文本快照。

`POST /api/auth/registration/challenges` 输入 `{channel, contact, locale, captchaProofId?}`，202 输出 `{challengeId, expiresAt, resendAt}`。最大 4 KiB。

`POST /api/auth/registration/challenges/verify` 输入 `{challengeId, code, captchaProofId?}`，输出 `{proofId, expiresAt}`。最大 2 KiB。六位码按字符串处理；失败计数提交后再转换为 HTTP 错误。

`GET /api/auth/registration/state` 输出：

```ts
type RegistrationState = {
  state: 'NONE' | 'PENDING' | 'VERIFIED' | 'CONSUMED' | 'EXPIRED' | 'LOCKED' | 'SUPERSEDED';
  channel: 'EMAIL' | 'SMS' | null;
  maskedContact: string | null;
  challengeId: string | null;
  proofId: string | null;
  expiresAt: string | null;
  resendAt: string | null;
  receipt: RegistrationReceipt | null;
};
type RegistrationReceipt = {
  receiptId: string;
  username: string;
  nextPath: '/user/login';
  wechatBinding: {
    status: 'NOT_REQUESTED' | 'BOUND' | 'FAILED';
    reason: string | null;
  };
};
```

只恢复当前会话的状态和脱敏联系方式，不能恢复密码或验证码。已消费挑战可以恢复原回执，不能再创建账号。

`POST /api/auth/registration/complete`：

```ts
type RegistrationCompletion = {
  proofId: string;
  username: string;
  displayName: string;
  password: string;
  policyVersion: number;
  agreementVersion: number;
  acceptAgreements: boolean;
  requestId: string;
  captchaProofId?: string;
  profileSchemaVersion?: number;
  profileValues?: Record<string, string>;
  wechatRegistrationProofId?: string;
};
```

201 输出 `RegistrationReceipt`。首次成功和相同内容的幂等重试使用相同回执。业务指纹包含绑定于当前会话的注册联系方式证明、账号资料、密码、协议/策略/字段版本及可选微信证明；排除一次性 `captchaProofId`，这样用户在网络响应丢失后取得新 CAPTCHA 证明，也能以原 `requestId` 回放已经提交的回执。回放先于当前策略及 proof 消费，不产生新写；同 `requestId` 的业务内容改变仍返回409。确认密码仅在客户端比较。严格拒绝未知字段，尤其角色、组织、权限、状态和额度字段；不通过宽松反序列化静默接受这些字段。

附加字段使用 `GET /api/auth/registration/profile-schema` 的公开投影和共享字段 renderer；最多显式公开 20 个 custom 字段，每字段最多200个选项、总输出选项最多1000、投影 UTF-8 JSON 最大1 MiB。后台发布位置时批量检查限额，拒绝过大字典，不静默截断。字典后来增长超限时投影返回受控 `registrationProfileSchemaUnavailable`；注册仍允许不提交可选附加资料，并明确提示暂不可用。

完整提交最大 128 KiB，字符串按现有字段类型限制。仅当提交附加字段时要求对应 schemaVersion；没有附加字段不因无关布局变更或可选投影故障阻止注册。字段值与账号、联系方式、默认角色和协议接受在同一事务创建，不能提前保存半成品资料。

微信附加绑定始终可选。没有提供方时只允许省略 proof；提供了未知或无效证明仍不自动绑定。提供方已接入后，绑定失败通过回执报告并保留合法注册成功，不能复用 LOGIN/BIND 证明。

## 验证码直接登录

`POST /api/auth/login-code/challenges` 输入 `{channel, contact, locale, captchaProofId?}`，202 输出与注册相同的 `{challengeId, expiresAt, resendAt}`；不存在或不可用的账号也返回结构和寿命相同的随机挑战。最大 4 KiB。

`POST /api/auth/login-code/complete` 输入 `{challengeId, code, captchaProofId?}`，输出统一 `LoginResponse`，最大 2 KiB。服务端只能通过已验证且唯一的联系方式确认已有账号，并在完成时重查联系方式和凭据版本、全局/角色策略及 MFA/CAPTCHA。不能自动创建账号，不能提前写入完整 SecurityContext。

联系方式、注册码、登录码、绑定码、找回码及再次验证用途严格隔离。手机注册保持关闭，直到独立手机找回流程实现且通过验收；仅提供短信登录不满足该前置条件。

## 管理注册配置

`GET /api/platform/settings/registration` 返回 `{version, enabled, channels, defaultRoleId, readiness}`。`readiness` 包含 `{emailAvailable, smsAvailable, phoneRecoverySupported, agreementsAvailable, defaultRoleEligible, maintenance}`，仅向获准管理员展示；读取要求全局设置权限。默认角色名字和详情通过角色候选端点取得，不能因为读设置而泄露原本不可读的角色数据。

`PUT /api/platform/settings/registration` 输入 `{version, enabled, channels, defaultRoleId, changeReason}`，返回更新后的管理配置；要求全局设置写和全局角色写。默认角色只能为启用、SELF 范围及显式准入权限的普通角色；被引用期间所有角色删除、停用和权限修改路径都执行引用保护。

`GET /api/platform/settings/registration/roles?page=1&pageSize=20` 返回现有 `PageResult`：条目为 `{id, code, name, version, eligible, reason}`。同时要求全局设置读和角色读，最大 pageSize=100。不可选原因用于后台解释，不能转发匿名。
