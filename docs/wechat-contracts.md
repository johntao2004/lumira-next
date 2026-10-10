# 微信网站扫码登录与绑定接口契约

本文冻结 P07 的应用交换结构，遵守 [统一设计](role-authentication.md)。首版只接微信开放平台网站应用，默认关闭，真实授权需要有效 AppID、密钥和批准的回调域。登录、本人绑定、注册附加绑定分别使用 LOGIN/BIND/REGISTRATION，不能互换证明。

官方协议入口为[网站应用微信登录](https://developers.weixin.qq.com/doc/oplatform/Website_App/WeChat_Login/Wechat_Login.html)。当前文档读取未成功，适配器实施时须继续核对官方协议及现有原项目实现；本文件不把协议联调标记为已完成。

## 公共扫码与结果

`POST /api/auth/wechat/attempts`：输入 `{purpose:'LOGIN'|'REGISTRATION', registrationProofId?:string, captchaProofId?:string}`，输出 `{attemptId, purpose, authorizationUrl, expiresAt}`。REGISTRATION 必须持有同会话有效联系方式注册证明。BIND 不允许通过此匿名路径发起。

授权 URL 由服务器按固定微信域名和配置回调生成，不接受客户端回跳 URL。attempt/state 存在共享数据库，绑定匿名/已认证会话摘要、用途、应用/凭据政策修订，最长 5 分钟、一次认领。不能按昵称、UnionID、头像、联系方式自动合并身份。

`GET /api/auth/wechat/callback` 接收微信 state/code 或取消结果。先原子认领并校验会话及用途，再于数据库短事务之外交换 code；返回后重查配置和账号状态。网络错误、应用改变、超时或重复回调均不能产生身份证明。响应禁缓存、无 Referer，并以 303 跳转固定 `/user/wechat-result`，不携带 state/code/token；代理与应用访问日志不保留回调查询串。

`GET /api/auth/wechat/result` 只返回当前会话最近尝试：

```ts
type WechatResult = {
  status: 'NONE' | 'PENDING' | 'READY' | 'UNBOUND' | 'FAILED' | 'EXPIRED' | 'CANCELLED';
  attemptId: string | null;
  purpose: 'LOGIN' | 'BIND' | 'REGISTRATION' | null;
  expiresAt: string | null;
  login: LoginResponse | null;
  wechatRegistrationProofId: string | null;
  identity: { nickname: string | null } | null;
  failureCode: string | null;
};
```

身份摘要只包含可安全展示的昵称，按文本渲染，不返回 OpenID/UnionID、外部 access token 或任意远程头像 URL。LOGIN 已绑定账号调用统一 PrimaryAuthenticationPort，结果可能仍需 CAPTCHA/MFA/受限首次绑定；未绑定仅返回 UNBOUND，可转普通登录后绑定或公开注册，不创建账号。REGISTRATION 只签发独立注册绑定 proof；BIND 只进入本人确认页。

结果页读取状态并依据 purpose 返回登录、注册或账号安全页面；恢复码不在此返回。刷新仅恢复脱敏状态，客户端不会持久存储外部 code 和任何 proof。

## 本人绑定与解除

`GET /api/account/wechat`：输出 `{version, bound, usable, identity:{nickname:string|null}|null}`。完整认证、本人的真实账号范围；不能借角色预览操作绑定。

`POST /api/account/wechat/binding/start`：输入 `{version, replace:boolean, reauthenticationTicketId:string}`，输出扫码 attempt 结构，固定 purpose=BIND。再次验证动作为 WECHAT_BIND；已有绑定只在 replace=true 且当前 version 相符时允许发起替换。扫码回调不写绑定。

`POST /api/account/wechat/binding/confirm`：输入 `{attemptId, version, confirm:true}`，输出本人绑定摘要。要求同一完整认证会话、当前账号、未过期 BIND 证明及发起时再次验证/认证修订仍有效。用户必须看见本账号和微信摘要后明确确认，不能在回调或页面挂载时自动提交。

`DELETE /api/account/wechat`：输入 `{version, reauthenticationTicketId}`，204；再次验证动作 WECHAT_UNBIND。解绑或替换须保留至少一种当前政策允许且账号可用的主方法，并撤销受影响的微信登录挑战及相关会话。禁止把身份从另一个账号转移过来。

绑定唯一键 `(provider, appId, openId)`；数据库唯一约束负责并发裁决。注册附加绑定调用窄 port，在建号事务内尝试消费独立 REGISTRATION proof；过期、被占用和无效证明以可选绑定失败返回回执，不回滚合法账号创建。不能消费 LOGIN/BIND 证明。无提供方时不展示该选项。

注册编排使用 `identity.api.WechatRegistrationBindingPort.consumeForRegistration(wechatRegistrationProofId, registrationContactProofId, newAccountId, request)`，返回 `{status:NOT_REQUESTED|BOUND|FAILED, reason}`；必须加入账号创建事务。未传微信 proof 不调用，提供方缺失而请求带 proof 时受控返回 FAILED。proof 同时绑定联系人注册证明、当前会话及 REGISTRATION 用途。数据库唯一冲突使用 `ON CONFLICT` 或 savepoint 裁决，不能捕获唯一异常后继续使用已失败的 PostgreSQL 事务。

## 管理设置

`GET /api/platform/settings/wechat` 返回 `{version, enabled, appId, publicOrigin, timeoutSeconds, appSecretConfigured, ready}`，全局设置读取权限。`publicOrigin` 为服务端公开同源 origin，不含任意路径或查询；回调固定 `/api/auth/wechat/callback`。生产 origin 要求 HTTPS，回跳为同源固定页面。

PUT 输入 `{version, enabled, appId, publicOrigin, timeoutSeconds, appSecret:{action:'KEEP'|'REPLACE'|'CLEAR',value?:string}, changeReason}`，全局设置写。密钥经 SecretStore 加密保存，不返回明文。更换有现存绑定的 AppID 默认拒绝 `wechatAppInUse`，需独立受控迁移，不把旧应用身份拿来登录新应用。停用或清除配置执行引用保护，不能使仅可用微信的有效账号失去可用登录方法。

所有 JSON 输入有界，微信出站域名固定、网络总超时、响应体上限，不把完整敏感 URL/响应写入日志。模拟网络/回调测试与真实微信网站授权、取消、扫码和绑定分别留证；未具备真实配置时外部验收保持未验证。
