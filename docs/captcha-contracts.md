# 人机验证契约

Lumira Next 自行生成和核验人机验证，不调用第三方 CAPTCHA 脚本、云 API 或云凭据。设置默认关闭，启用后可用六位数字图片或滑块。语音替代使用随程序发布的中文和英语数字 WAV 素材，在服务端组合成单条音频。

## 动作与证明

每个动作使用 OFF、RISK 或 ALWAYS 策略。RISK 基于持久限流与失败事实；登录主凭据通过后，真实角色的附加要求使用 POST_PRIMARY_AUTH 和当前受限登录 attemptId。注册完成时使用当前默认角色的附加要求。客户端不能指定角色或策略。

证明绑定会话摘要、动作、必要的 attemptId 和策略修订。挑战有效 120 秒；每个会话和动作只保留一个活动挑战；刷新会使旧挑战失效并撤销其 proof。错误答案最多允许五次。成功 proof 有效 300 秒，在受保护动作的提交事务中只能消费一次。

## 本地挑战资源

图片文字和滑块图像通过 Java2D 生成 PNG，PNG 不携带答案元数据。滑块背景采用随程序发布的本地图片资源。语音使用 captcha/audio/{zh,en}/{0..9}.wav 数字片段组合，并在片段间加入约 180ms 静音；运行时不依赖操作系统 TTS 或外网。

答案和滑块目标位置以 PublicAuthCrypto.fingerprint keyed HMAC 存入数据库，HMAC 上下文含挑战 UUID。服务端不保存明文答案或目标坐标；验证使用常量时间摘要比较。答案和坐标都不会出现在 API 响应中。

readiness 要求部署密钥 LUMIRA_SECRET_KEYS、已配置挑战类型所需图像资源，以及中英文完整语音素材。资源缺失时不宣称无障碍替代可用；应用启用或角色强制前验证 readiness，运行时故障失败关闭。

旧 Tencent AKID、AppID 和 SecretStore 密钥保留于原数据库及 SecretStore，以免破坏历史配置，但不再出现在管理 API 中，也不会被验证码调用或改写。

## 公共 API

POST /api/auth/captcha/challenges 输入 {action, attemptId?, accessible?, locale?}。locale 仅决定中文或英语语音素材，不接受资源路径或提供方。

```ts
type CaptchaChallenge = {
  challengeId: string;
  action: CaptchaAction;
  attemptId: string | null;
  expiresAt: string;
  provider: 'LOCAL';
  type: 'IMAGE_TEXT' | 'SLIDER' | 'AUDIO';
  accessibleAlternativeAvailable: boolean;
  imageDataUrl?: string;
  backgroundDataUrl?: string;
  pieceDataUrl?: string;
  audioDataUrl?: string;
  width: number;
  height: number;
  pieceWidth: number;
  pieceHeight: number;
  pieceY: number;
};
```

IMAGE_TEXT 返回 imageDataUrl；SLIDER 返回 backgroundDataUrl、pieceDataUrl、图像自然尺寸和 pieceY；AUDIO 返回 audioDataUrl。音频资源的 MIME 类型为 audio/wav，图片资源为 image/png。

响应始终包含 width、height、pieceWidth、pieceHeight 和 pieceY 数值字段。AUDIO 将这五个尺寸/坐标字段设为 0；音频客户端只需读取 audioDataUrl，无需对这些字段要求正数。

POST /api/auth/captcha/verify 输入最大 8 KiB。IMAGE_TEXT 和 AUDIO 使用 {challengeId, answer}，answer 必须为六位 ASCII 数字。SLIDER 使用 {challengeId, sliderX, durationMs, trail}；sliderX 与 trail 坐标采用原始 320×160 背景图像素，时间单位为毫秒，trail point 为 {x, y, elapsedMs}。至少提供起点和终点；离散键盘轨迹可用，无位移不能通过。服务端按目标位置 ±8 像素容差核验。鼠标时长/轨迹字段用于边界和输入形状检查，不作为抵御自动化的强保证。

成功返回 {captchaProofId, action, attemptId, expiresAt}。challengeId、会话、动作、attemptId、策略修订、尝试次数、到期时间及一次性消费仍由服务端数据库状态机验证。过期、跨会话/动作/尝试、重放、达到失败上限或策略变化均拒绝。

POST /api/auth/login/captcha/confirm 输入 {challengeId, captchaProofId}，只能推进当前会话对应的 CAPTCHA_REQUIRED 登录状态；之后仍可能要求 MFA 或受限首次绑定。

GET /api/auth/login/state 返回当前会话统一 LoginResponse；POST /api/auth/login/cancel 只能取消同一受限登录尝试。普通验证码面板取消只关闭面板，不消费已有 proof。

密码登录以可选 captchaProofId 提交 PASSWORD_LOGIN proof；Passkey 认证 options 以可选 captchaProofId 提交 PASSKEY_LOGIN proof。Passkey 完成时仍重查当前政策、风险和 challenge 绑定。

## 管理 API

GET /api/platform/settings/captcha 返回版本、修订、数据、密钥状态和 readiness：

```ts
type CaptchaSettings = {
  version: number;
  revision: string;
  data: {
    enabled: boolean;
    provider: 'LOCAL';
    type: 'IMAGE_TEXT' | 'SLIDER';
    actions: Record<CaptchaAction, 'OFF' | 'RISK' | 'ALWAYS'>;
  };
  secretEncryptionConfigured: boolean;
  readiness: { configured: boolean; accessibleAlternativeAvailable: boolean };
};
```

PUT /api/platform/settings/captcha 输入 {version, data, changeReason?}。changeReason 可省略或留空；此时服务端写入固定缺省原因，并照常记录设置审计事件。缺失的动作设置按 OFF 保存，响应返回与后续 GET 一致的规范化 data、version、revision 和 readiness。保留版本冲突、修改原因校验、全局授权和认证策略写锁；启用 CAPTCHA 或设置角色 ALWAYS 时必须通过真实本地资源与语音 readiness。数据库中的历史 Tencent 凭据列不更新，原 SecretStore 值不读取。

Flyway V43 为挑战增加 keyed verification_digest，并使升级前的活动 Tencent 挑战及未消费 proof 失效；全局动作设置保持原值，只轮换设置修订版本。
