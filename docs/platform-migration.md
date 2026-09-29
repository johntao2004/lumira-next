# 平台全量适配执行清单

## 2026-09-16 平台脚手架闭环复验（当前基线）

本轮先完成公共平台脚手架，业务流程迁移仍按范围排除（赛事、报名、支付交易、评审、证书、项目/团队及官网业务展示）。当前工作区的可复验结果如下：

- 单文件 PostgreSQL 初始化基线已包含完整的 31 个内置导航入口，含 Passkey 设置入口；`database-schema-smoke.py` 对表、约束、索引、权限和入口集合做确定性校验。
- 前端所有静态路由都有组件注册，动态 PAGE 只允许受限 `componentKey`，双层/分区导航共享服务端权限过滤后的运行时树；导航动画、自动隐藏、滚动、键盘和移动端边界均纳入浏览器回归。
- 公共 UI 统一复用 `PlatformForm`、`FormDrawer`、`ResourceTable`、`AsyncButton`、`FeedbackBubble`、`QueryFailure` 和设置历史组件；短时操作结果使用气泡反馈，`Alert` 仅保留当前页面必须持续看到的状态或操作指导。
- 本地证据：`pnpm test`（17/17）、`pnpm typecheck`、`pnpm build`、`pnpm test:browser` 全部通过；`database-schema-smoke.py`、`smoke.py`、Passkey 回归全部通过；使用项目 JDK 25 执行 `./mvnw -q clean verify` 退出码为 0；前后端运行时分别在 8010/8090 返回 HTTP 200。
- 字典文件流已覆盖 TXT、XLS、XLSX 的真实浏览器选择、预览、错误反馈、导入、导出和刷新持久化；旧段落中“文件流尚未接入”的描述是按时间记录的历史事实，不能作为当前缺陷判断。

当前剩余的是生产边界验收：外部邮件/短信供应商、对象存储与备份恢复、制品签名/漏洞扫描/滚动发布、跨实例缓存与容量/故障转移压测，以及 CI 远端执行。它们不等同于本地平台脚手架功能缺失。

## 2026-09-15 数据库基线收敛（最新）

原 V1–V48 已合并为不可变的 `backend/src/main/resources/db/migration/V1__platform_initialization.sql`，身份模块另有 `V2__identity_argon2_password_hash.sql` 追加迁移。生产 Flyway 按版本执行这两份 SQL；历史增量文件移到测试资源，仅用于升级行为回归。后期维护、备份恢复和在线升级边界见 [数据库初始化说明](database-initialization.md)。

目标：迁移、复用或重构旧系统的公共平台功能，统一组件和接口规范，修复当前问题。赛事、报名、支付交易、评审、证书、项目/团队等业务流程不纳入本轮；不能用新增静态页面替代实际能力。

最新复验（2026-09-15）：反馈公共模块、共享富文本编辑器、共享图片文件引用、模块与字典策略、权限/审计、账户抽屉交互、全局在线用户会话管理、平台运行监控、V42 版本化动态导航、安全动态 PAGE 入口、V43 反馈创建草稿适配、V43 路由唯一性、V44 组件键注册契约、V45 敏感词策略模块、V46 运行时本地化、Outbox 消费/保留边界、死信人工重放审批、V47 短信队列与账号手机验证及 V48 短信队列运维查询已接入。用户管理补齐按数据范围过滤的 CSV 导出，管理员“全部文件”中心和通知中心已接入：文件列表复用 BlobStore、权限、版本删除和审计；通知发布支持全部/指定账号、站内链接和过期时间，复用 ResourceTable、FormDrawer、PlatformForm 与八语言文案。前端入口复用 ResourceTable 工具栏，接口和浏览器下载/发布回归覆盖。登录限流和超长密码拒绝现在会为已识别账号写入安全事件，审计流增加默认 365 天的有界批量保留清理；安全策略页面已接入密码规则、登录/找回限流和验证码有效期/冷却/配额，并通过版本历史与回滚闭环。新增默认关闭的数据库化 Passkey/WebAuthn 公共适配，挑战会话绑定、过期和原子消费，认证校验 Origin/RP ID、UP/UV、ECDSA/RSA 签名和计数器。统一表单入口 `platform/components/platform-form.tsx` 已接管所有 Ant Design Form，集中提供垂直布局、Form 子组件和 ConfigProvider 间距令牌，登录、设置、用户、组织、字典、账户抽屉、通知发布等页面的表单项统一为 16px 间距、操作区统一为 10px 间距。启用本地 PostgreSQL 夹具的后端 `clean verify` 共 256 项测试，失败 0、错误 0（88 项外部依赖测试按环境跳过）；前端使用 Node 24 完成类型检查、生产构建、17 项仓库测试和全量浏览器回归，readiness 返回 HTTP 200。已清理 Ant Design 6 的 `List`、`direction`、`valueStyle` 等弃用调用，并补充非法短信凭据的终止错误分类。声明式模块包登记、摘要幂等、代码版本/依赖护栏和激活/拒绝状态机已接入；邮件/短信终态队列新增有界保留清理；动态制品扫描、数据库变更、滚动发布、赛事/外部消费者注册和生产容量/高可用验收继续保留为后续边界。

## 范围与验收

| 平台能力 | 原实现定位 | 当前状态 / 验收要求 |
|---|---|---|
| 身份与账号 | lumira-auth；system/ProfileController、OnlineSessionController | 登录、修改密码、本人会话管理、全局在线用户强制下线和手机号验证已接入；完整账号生命周期、外部短信送达仍待验收 |
| 用户/角色/组织/数据权限 | lumira-system/modules/system | 用户/角色/组织及关联、用户与组织可见性数据范围已接入；待其他资源范围、委派管理与全局管理员专项验收 |
| 动态菜单、字典、资料字段 | settings/menus、dicts、profile-fields | 动态菜单 V42 已接入：树、排序、启停、版本/内置保护、运行时权限过滤和管理页；安全动态 PAGE 可进入受控占位页；可安装的自定义页面组件注册、字典/资料字段的后续业务消费仍待迁移 |
| 品牌、协议、维护、水印 | settings/personalization | 品牌、协议、维护、水印已按分区保存并接入版本历史/回滚；外部发布与跨实例缓存仍待验收 |
| 安全与验证配置 | settings/security、verification | `/dashboard/security` 已接入密码规则、登录/找回限流、验证码 TTL/冷却/请求配额/失败上限，版本化审计与回滚；会话 Cookie、CAPTCHA/MFA 供应商和外部验证渠道仍由运行时/后续业务接入 |
| 文件与存储 | lumira-file | 账号文件上传、配额、元数据/列表、下载、版本化删除和头像/规范化图片已接入本地 BlobStore；生产对象存储、跨实例一致性及备份恢复仍待验收 |
| 通知与渠道 | lumira-message、settings/notifications | 站内通知、管理员全部/指定账号发布、SMTP 配置/持久化投递队列、短信 webhook 配置/加密队列/失败状态和运维查询已接入；真实外部发送/接收、供应商回执和生产投递仍待验收 |
| 审计、监控、配置版本 | system/audit、monitor、config | 操作审计查询与只读运行监控已接入；配置版本/回滚及完整事件审计待迁移 |
| 异步任务、调度、Outbox | lumira-async、lumira-quartz、system/task | Outbox 幂等、重试、死信、租约消费 worker、已投递保留清理和死信人工重放审批已接入；赛事/外部消费者和通用调度仍待迁移 |
| 插件平台 | lumira-plugin、settings/plugins | 已接入声明式包登记、摘要幂等、激活/拒绝和代码版本/依赖兼容检查；制品扫描、部署流水线、动态执行与生产多实例升级仍待验收 |
| 本地化管理 | lumira-localization | V46 已接入八语言运行时覆盖、版本化 CRUD、JSON 导入、启停、发布计数器和公开语言包；新增语言仍需同步 Ant Design locale 与后端白名单 |
| 草稿、反馈、敏感词等公共工具 | system/draft、workorder、sensitive | 通用草稿、反馈及反馈创建草稿已完成公共边界适配；敏感词策略已完成词条管理、批量导入、检查与反馈阻断 |
| 发布与运行 | deploy、CI | 待当前构建、容器、恢复和容量验证；保留旧数据与回退路径 |

### 声明式模块包交接闭环

`platform_module_package` 是部署交接表，不是插件执行表。管理端通过 `/api/platform/modules/packages` 登记规范化 manifest，服务端保存 artifactRef、artifactDigest 和 manifestDigest，禁止路径穿越、非 HTTPS/OCI/S3 引用及不受支持的 API 版本。重复登记按模块/版本/制品摘要幂等；激活和拒绝携带 `revision`，由事务 advisory lock `481027`、行锁和状态检查保证并发安全。激活前必须匹配当前静态 `PlatformModule` 版本、依赖集合及启用状态，旧激活包标记为 `SUPERSEDED`。后续部署系统仍需负责签名/漏洞扫描、制品上传、数据库 schema 变更、滚动发布、对象存储、备份恢复和多实例回滚；应用进程不会下载或执行登记制品。

### V42 动态导航闭环

- `platform_navigation_menu` 保存菜单父子关系、稳定编码、国际化键、页面路径、权限/全局范围、模块依赖、排序、启停、内置标识和乐观版本；当前迁移种子包含 31 个内置入口，并由 schema smoke 锁定入口集合，避免路由代码与数据库基线漂移。
- `/api/platform/navigation` 返回当前账号可见的运行时树；管理端 `/api/platform/navigation/menus` 提供新增、编辑、启停、软删除和自定义节点排序。写操作经过事务锁、全局权限、父子循环校验、内置保护、子节点删除保护和版本检查，并记录审计事件。
- `NavigationPage` 复用 `ResourceTable`、`FormDrawer`、`DeleteAction` 和统一 API/CSRF；双层与分区布局共享运行时快照。PAGE 路径由后端约束为安全单段 slug，V43 唯一索引与并发校验避免地址冲突，V44 的 componentKey 只作为受限标识交给前端代码注册表，Umi 路由对未知但已授权或未注册的入口展示受控占位页，不渲染数据库 HTML 或执行任意代码。导航 HTTP/浏览器脚本已加入本地回归与 CI。

### V43 反馈创建草稿适配

- 反馈创建抽屉复用账号级 `useAccountDraft` 与 `DraftActions`，稳定键为 `feedback.create`；草稿只保存标题、优先级和安全的富文本 HTML，不携带权限、记录版本或文件元数据。
- `feedback-draft.ts` 提供版本化 `{schema: 1, values}` 编解码，校验字段类型与长度上限，拒绝不兼容或不可信载荷。打开抽屉时可恢复草稿，取消后再次打开保留内容，提交成功后只清理已消费的修订；冲突或清理失败均保留可恢复状态并给出本地化反馈。
- 反馈浏览器冒烟已覆盖保存、取消/重开恢复、富文本恢复、成功提交后的草稿清理和 390px 抽屉边界；前端 17 项 Node 测试、类型检查和生产构建通过。

### V43/V44 动态页面地址与组件注册契约

- V43 为存活 PAGE 的 `sectionKey` 和 `path` 建立 PostgreSQL 部分唯一索引；服务层在写入前做快速冲突检查，并将并发唯一键冲突转换为可本地化的 409，避免运行时按树顺序随机命中页面。
- V44 为菜单返回 `componentKey`，后端只接受受限标识。前端 `page-component-registry.tsx` 只解析代码显式注册的组件，重复注册会失败；未注册键使用受控占位页，不读取数据库 HTML、模板或代码。
- 导航浏览器冒烟已用已注册的 `platform:info` 组件走通动态 PAGE，并保留未知组件的安全降级；可安装模块包、热升级和跨实例注册仍需后续交付。

### V45 敏感词策略模块

- `platform_sensitive_word` 通过增量迁移建立软删除、乐观版本、启停、分类、级别和动作约束；唯一索引与 `ON CONFLICT DO NOTHING` 同时覆盖管理写入和批量导入并发。
- `SensitiveWordMatcher` 使用不可变 Aho-Corasick 自动机，并在发布新快照时替换原子引用；匹配统一执行 NFKC、大小写和空白归一化，检查接口只返回脱敏词片段，不把原词写入运行日志。
- 管理接口提供分页搜索、创建/编辑、启停、软删除、批量导入和文本检查，分别受读取、写入、导入全局权限保护并写操作审计。`FeedbackService` 在标题和清理后的详情文本进入存储前执行阻断策略，普通账号不获得词典读取权限。
- `/dashboard/sensitive-words` 复用 `ResourceTable`、`FormDrawer`、`DeleteAction`、`AsyncButton` 和八语言目录；真实 HTTP 覆盖导航、创建/命中/阻断、禁用即时生效、导入重复护栏，浏览器回归覆盖页面创建、检查、启停、窄屏无溢出和 pageerror。

### V46 运行时本地化模块

- `platform_localization_entry` 以消息键为唯一事实源，保存命名空间、默认文案、受控语言翻译 JSON、启停、乐观版本和审计字段；部分唯一索引和 `ON CONFLICT DO NOTHING` 覆盖并发导入。
- `/api/platform/localization/entries` 提供分页查询、创建、编辑、启停和软删除；`/import` 受 5,000 条上限保护，`/publish/{locale}` 递增语言包发布版本。独立读取/写入/导入全局权限和模块状态约束管理接口。
- `/api/public/localization/bundle/{locale}` 只返回启用文案，按当前语言、基础语言、简体中文、默认文案顺序回退，并以短 TTL 进程缓存降低公开读取压力。前端 Provider 在静态词典上加载运行时覆盖，网络失败自动保留静态文案。
- `/dashboard/localization` 复用 `ResourceTable`、`FormDrawer`、`DeleteAction`、`AsyncButton` 和八语言目录；浏览器回归覆盖页面、创建、发布、公开 bundle、删除、响应式无溢出和 pageerror。

### V47 短信队列与账号手机验证

- `platform_sms_delivery` 保存加密短信正文、投递状态、尝试次数、租约到期和 lease token；worker 使用有界虚拟线程并发，数据库租约与 token fencing 防止旧 worker 覆盖新状态。供应商 HTTP 调用不在数据库事务内，401/403 和非法 header 凭据终止重试，连接失败与结果不确定分别进入可恢复状态；日志和管理接口不包含密钥或验证码，完整手机号只向当前账号的状态接口返回。
- `SmsSettingsService` 将 webhook 地址、发送方、模板和超时放入版本化设置，凭据只通过 `SecretStore` 的加密 REPLACE/KEEP/CLEAR 操作保存。外部地址强制 HTTPS，本地回环允许 HTTP 便于开发；启用短信前必须同时提供凭据和密钥。
- `AccountPhoneService` 要求当前密码、E.164 手机号和短信服务可用；验证码只保存摘要，带冷却、按手机号/账号限流、每日上限、失败次数、过期和乐观版本，一次确认成功后立即失效。唯一手机号冲突、错误密码、未配置供应商和过期挑战均返回受控本地化错误。
- `/dashboard/sms` 复用设置分区、历史/回滚和八语言表单；账号资料抽屉提供手机号请求/确认入口，独立维护密码输入并在错误或关闭后清空。浏览器回归覆盖未配置供应商反馈、密码清理、桌面/390px 边界和无 pageerror；未发送外部短信。

### V48 短信队列运维查询

- `SmsDeliveryQuery` 和 `GET /api/platform/sms-deliveries` 复用邮件投递的分页契约，支持 1–100 条分页、状态白名单和任务 ID 字面搜索；查询在 `REPEATABLE_READ` 快照中执行并要求全局 `platform:audit:read`，不解密 payload、不返回手机号或 lease token。
- `/dashboard/sms-deliveries` 复用 `ResourceTable`、状态筛选、刷新失败保留数据和八语言消息；真实浏览器回归覆盖 FAILED/ACCEPTED/UNCERTAIN 行、筛选、搜索、503 重试、无 pageerror 和桌面无横向溢出。当前仍没有自动重发或人工改写终态，结果不确定必须由后续受控运维流程处理。

### 安全策略配置闭环

- `SecuritySettings` 是一个带边界校验的强类型策略记录，覆盖密码最小长度/字符要求、登录与找回密码的账号/来源限流，以及验证码有效期、冷却时间、每分钟/每日配额和失败上限。密码校验按 Unicode 码点和 256 UTF-8 字节上限执行；新摘要使用 Argon2id，遗留 BCrypt 仅按 72 字节兼容边界读取。
- `/api/platform/settings/security` 复用 `SettingStore` 的乐观版本、审计、历史详情和回滚契约；`SecuritySettingsService` 对登录热路径使用 2 秒有界缓存，保存后立即刷新缓存。PasswordService、PasswordRecoveryService、登录/找回/邮箱/手机验证码限流器和挑战存储均读取同一份运行时策略。
- `/dashboard/security` 复用共享 `SettingsSection`、`SettingsHistory` 和八语言目录；浏览器回归覆盖全部字段读写、版本历史、回滚、刷新持久化、pageerror 和横向溢出。会话 Cookie 生命周期与 CAPTCHA/MFA 供应商没有伪造配置项，需在实际运行时基础设施和后续业务接入后单独验收。

### Outbox 消费与保留边界

- `PlatformEventConsumer` 由模块代码显式注册 `(sourceType,eventType)`，启动时拒绝非法或重复注册；数据库菜单、事件类型和事件负载不能指定 Java 类或加载任意代码。
- `PlatformEventOutboxWorker` 使用虚拟线程和固定并发槽轮询 `SKIP LOCKED` 租约；消费器成功返回后才以租约令牌确认，失败写入受限错误码并沿用指数退避，旧租约仍由维护任务恢复。
- `PlatformEventOutboxStore.purgeDelivered` 只分批删除保留期外的 `DELIVERED` 记录，`PENDING`、`PROCESSING` 和 `DEAD_LETTER` 保留供恢复或人工审批。当前没有赛事或外部消息消费者，未宣称消息总线投递。
- `POST /api/platform/event-outbox/{id}/replay` 只接受 `DEAD_LETTER`，要求全局 `platform:events:write`、CSRF 和非空原因；行级锁先确认死信再清除租约/错误并重新排队。`platform_event_replay_approval` 保存操作者、原因和时间，状态重置、审批记录与审计在同一事务中完成；重复、过期或非死信操作返回受控冲突。
- 邮件和短信投递表现在由共享 `DeliveryRetention` 按终态和保留期有界清理，删除动作使用 `FOR UPDATE SKIP LOCKED`，不会抢占活跃租约；保留的审计事件不含加密正文。保留期和批量可通过 `LUMIRA_DELIVERY_TERMINAL_RETENTION`、`LUMIRA_DELIVERY_RETENTION_BATCH` 调整。

### 管理员全部文件中心与通知中心（2026-09-15）

- `GET /api/platform/files` 复用 `FileRepository` 的分页契约，在文件所有者、文件名和状态之外补充所属账号投影；`GET /api/platform/files/{id}/content` 流式读取 BlobStore，`DELETE` 使用版本号、文件引用保护、删除状态机和双阶段审计。全局读取/写入权限分别受 `platform:files:read`、`platform:files:write` 和 `AdministrationGuard.requireGlobal` 保护，账号“我的文件”接口仍保持所有者边界。
- `GET/POST /api/platform/notifications` 复用站内通知表和 `NotificationStore`：管理员可向全部启用账号或显式选择的账号发布通知，服务端限制收件人数量、标题/正文、站内链接和过期时间，正文以参数化自定义文案键呈现，不执行 HTML。发布写入 `notification.broadcast` 审计并保持与个人收件箱相同的已读/过期规则。
- `/dashboard/files-all` 和 `/dashboard/notifications` 复用 `ResourceTable`、`DeleteAction`、`FormDrawer`、`PlatformForm`、统一 API/CSRF 和八语言目录；浏览器回归验证全局列表、原始字节下载、版本删除、通知发布、页面无横向溢出和无 pageerror。权限种子同时加入 V1 基线、首次管理员引导和角色可授予目录。

## 代码统一规范

- 带文字的异步按钮复用 `platform/components/async-button.tsx`：传入本地化 `label` 和 `pending`，统一稳定的可访问名称、`aria-busy`、loading 和禁用状态。已有业务禁用条件仍通过 `disabled` 合并。普通同步按钮继续使用 Ant Design Button；组件禁用不能替代服务端幂等、权限或版本校验。
- 所有表单统一从 `platform/components/platform-form.tsx` 导入，不直接散落使用 Ant Design `Form`。该封装保留 `Item`、`List`、`Provider`、`useForm` 和 `useWatch` 等原生能力，并统一 `platform-form` 标识、默认垂直布局及全局表单间距令牌；新增表单必须复用它，操作按钮放在 `.form-actions` 或页面专用操作区。
- 非忙碌时保留 `disabled` 的 undefined 值，让 Ant Design Form/ConfigProvider 的只读上下文继续生效。Popconfirm 内置异步确认按钮通过 `okButtonProps` 设置本地化名称和忙碌状态，不能依赖图标产生的可访问名称。

- platform 为公共底座，features 为功能模块。页面只负责路由装配；表格、筛选、分页、编辑抽屉、删除确认、权限按钮、错误/空状态使用共享组件。
- 所有 API 通过统一 client；统一会话失效处理、CSRF、错误码和分页契约。列表由服务端分页，禁止静默 LIMIT 截断。
- 后端按领域分离 Controller / Service / Repository；写操作事务化并审计，输入验证，接口独立授权。
- 新环境通过单文件 Flyway 基线初始化；现有生产库的后续结构变更不能覆盖已执行的基线，需采用受评审的短期升级迁移并在完成后重新生成基线；新旧库数据不混用。
- 导航结构、权限标识、页面标题使用单一注册定义；主题/布局偏好共享，布局不随子路由重挂载。
- 文案进入八语言词典，禁止组件内复制多语言分支。主题使用 tokens，避免重复 CSS 覆盖堆叠。
- 公共工具优先纯函数复用；只为真实行为边界写测试，包含越权、并发、失败和回滚路径。
- 每项完成证据：代码与数据结构 + 接口验证 + 页面真实操作 + 刷新持久化；部署与高可用另行验证。

## 当前修复记录

- [x] Drawer width 迁移 size，消除弃用 API。
- [x] 统一后台 API 401 处理，登录后返回安全校验后的原地址。
- [x] 刷新时主题/语言/导航偏好首屏一致；`scripts/preferences-browser-smoke.mjs` 验证首份 HTML、刷新、跨标签同步和旧值迁移。
- [x] 用户列表服务端分页、名称/账号搜索、通用 ResourceTable；105 条记录跨页与参数边界实测通过。
- [x] 用户新增、编辑和启停；独立写权限、版本冲突、管理员保护、事务审计记录。
- [x] 用户角色与组织关联接口及共用分配界面已接入；浏览器组织跨页提交仍待补回归。
- [x] 全量浏览器回归，含自动隐藏、键盘、窄屏、会话过期；2026-09-15 全量脚本通过。

本清单是执行跟踪，不是完成声明。后续应依据源代码盘点补充遗漏的公共能力，不能以此缩减原目标。

- [x] 当前用户修改密码：原密码校验、长度/字节上限、禁止原密码复用、已有会话退出；临时账号双会话 HTTP 测试通过。

## 2026-09-09 后续修复

- [x] 启动脚本复制独立运行 JAR；真实停止/重启及 readiness 检查通过，后续 Maven 构建不覆盖运行产物。
- [x] 遗留 BCrypt 摘要超过 72 字节时返回校验失败；新 Argon2id 摘要支持最多 256 UTF-8 字节，密码修改与双会话撤销回归通过。
- [x] 用户名称使用独立的八语言必填和长度提示。
- [x] 偏好 Cookie 异常输入、八语言和 false 设置往返测试；HTTP 原始 HTML 验证德语深色首屏，无需客户端执行后才切换。
- [x] 偏好跨标签、旧值迁移及导航布局回归已由 `scripts/preferences-browser-smoke.mjs`、`scripts/shell-responsive-browser-smoke.mjs`、`scripts/navigation-motion-browser-smoke.mjs` 覆盖。

## 角色授权底座（进行中）

- V3 增量迁移增加角色、角色权限、用户角色和统一有效权限视图；保留直接授权兼容。
- 登录、请求实时鉴权、最后管理员保护共同使用有效权限；角色写操作与用户停用共用事务锁。
- 新增角色分页/详情/权限目录、新增编辑启停、用户角色查询与分配 API。版本校验、权限白名单、禁止授予超出自身权限、审计入库。
- 本地 Maven verify、迁移启动及 roles-smoke 通过：角色重名/旧版本拒绝、已有会话赋权/撤权/停用即时生效、越权授予与未知权限拒绝。CI 已加入脚本，尚未运行远端 CI。
- 下一步：角色管理页面与用户授权抽屉复用 ResourceTable/FormDrawer；补角色删除、组织和数据范围，以及最后管理员/并发回滚专项测试。当前不能认定完整 RBAC 已完成。

### 角色页面接入

- 角色管理加入双层/树形导航及登录返回路径，接口独立授权。
- 复用 ResourceTable 和 FormDrawer，实现分页搜索、新增、详情编辑、启停、权限选择；字段文案覆盖八语言。共享抽屉新增 submitDisabled，避免只读或加载状态阻止用户关闭。
- 浏览器真实创建临时角色、详情权限回填、停用并刷新后持久化通过；验证角色已清理。TypeScript 与词典/公共函数测试通过。
- 用户分配角色抽屉、角色删除、组织数据权限尚待继续，不能视为公共平台迁移完成。

### 用户角色分配界面

- 用户列表的授权入口独立检查角色读写权限。打开时重新读取用户授权版本，提交后刷新用户和当前会话权限。
- 复用 FormDrawer、ResourceTable，扩展公共组件的抽屉宽度及跨页行选择；角色分页搜索保留已选项，八语言提示及 100 角色上限。
- 浏览器临时用户、21 个角色跨两页选择并真实保存，API 验证两个角色均持久化且用户版本递增；临时数据已清理。类型检查和 8 项现有测试通过。
- 尚需角色删除、组织数据范围与并发保护专项验证。

### 角色删除与编辑缓存修复

- 增加版本化 DELETE API，复用管理员事务锁及权限校验；已分配角色返回 roleInUse，删除与审计同事务。
- 新增共享 DeleteAction（确认、等待、失败反馈、八语言），角色页接入；末页最后一条删除后回退页码。
- 角色编辑重新打开时等待最新详情，防止旧表单值与新版本号组合覆盖数据。
- Maven verify、TypeScript、8 项前端测试通过；扩展 roles-smoke 覆盖已分配拒绝、旧版本、越权、成功删除及删除后 404，全部通过。
- 浏览器临时角色确认删除后列表为空；该操作保留真实审计记录。组织与数据范围及其他公共能力仍待迁移。

### 组织管理后端

- V4 增量迁移增加组织父子结构与用户组织关联表；组织读写权限加入权限目录及初始化管理员授权。
- 实现组织分页/搜索/详情、新增、编辑移动、启停及版本化删除。递归祖先检查拒绝循环；已有子组织或用户关联阻止删除，写入与审计同事务。
- 新公共 AdministrationGuard 统一事务锁及锁后权限复核，用户、角色、组织写操作接入，修复等待期间撤权仍可能继续执行的问题。
- 本地 Maven verify、V4 启动迁移、organizations-smoke（循环、占用、移动、版本冲突、回滚、删除）通过；users-smoke、roles-smoke 回归通过。CI 增加组织脚本，远端尚未运行。
- 待组织页面、用户归属及数据范围实际生效；仅有组织结构不等于已实现数据隔离。并发权限撤销专项测试仍需补充。

### 组织管理页面与复用选择器

- 组织页面接入两种导航及服务端权限，复用表格、编辑抽屉和删除确认，支持新增编辑、上级选择和启停。
- 新增 ResourceSelect：服务端搜索、分页继续加载、已有值独立回填；弹层挂载在表单内部，修复 Drawer 下候选项未进入无障碍树的问题。
- 八语言字段及错误文案齐全。浏览器创建父子组织，API 确认关联持久化；通过接口清理临时组织并保留审计记录。类型检查与 8 项测试通过。
- 组织列表的层级展示、用户归属及数据范围仍待继续；搜索分页选择器的多页加载还需专项浏览器验证。

### 用户组织归属与关联组件统一

- 增加用户组织查询/分配接口，使用公共写权限守卫、用户版本、组织存在校验、100 项上限和事务审计。
- 角色分配重构为 UserAssignmentsDrawer，角色/组织通过配置复用加载、分页选择、保存、错误及缓存更新；用户列表增加独立权限控制的组织入口。
- organizations-smoke 扩展验证分配、旧版本拒绝、无效组织回滚、成员占用删除保护、解除归属，全部通过；roles-smoke 回归通过，Maven verify、TypeScript 和 8 项前端测试通过。
- 浏览器确认组织归属入口、抽屉加载和取消；本轮保存验证为 HTTP 临时账号测试，未改动管理员组织。通用组件组织选择的多页浏览器提交仍需补回归。
- 数据范围过滤尚未接入，不能把归属记录当作已实现数据隔离。

### 用户数据范围（持续完善）

- V5 增加角色数据范围和 platform_visible_users：本人、本组织、组织及启用下级、全部；用户列表及总数同过滤，用户修改校验目标范围。按具体接口权限计算，不借用其他权限的宽范围。
- 新角色默认本人；已有角色兼容保留全部。角色表单增加八语言范围选择。直接授权仍为全局授权，目前没有面向普通操作者的直接授权写入口。
- 用户关联查询校验对应读权限范围；新建用户、角色全局配置、组织结构及归属写操作要求对应权限的全部范围，避免借关联扩大自身访问范围。
- V6 增加全局授权视图，最后管理员保护按全局管理范围计算。
- data-scope-smoke 验证四种范围、范围变更立即生效、跨组织修改拒绝、本人修改、不同权限范围不串用；角色和组织回归通过。Maven verify、TypeScript 通过。
- 本轮范围是用户数据。组织层级展示、受限管理员的委派管理、其他公共资源范围、并发撤权及全局管理员保护专项验收仍需继续，不能认定完整数据权限交付。

### 组织可见性数据范围收敛（2026-09-15）

- 在单文件 PostgreSQL 基线中新增 `platform_visible_organizations(actor, permission)`，复用用户数据范围的四种语义：SELF/ORGANIZATION 只返回当前账号所属的启用组织，SUBTREE 包含启用下级，ALL 或直接授权保持全局可见；范围按具体组织读取权限计算，不会被其他权限扩大。
- 组织分页列表、父节点筛选、详情和祖先链统一使用该函数。受限管理员无法通过详情或父节点参数读取范围外组织，统一按不存在返回，避免用页面隐藏代替后端边界；组织结构写入仍要求全局组织写权限。
- `scripts/data-scope-smoke.py` 新增组织列表、范围即时变更和范围外详情回归；`organizations-smoke.py`、用户回归、数据库 schema smoke 及后端 `clean verify` 均通过。
- 2026-09-28 V7 修正全局组织可见范围：ALL/直接授权可读取停用记录，SELF/ORGANIZATION/SUBTREE 仍只返回启用组织；组织 smoke 覆盖停用记录查询和删除。
- 其他带业务归属的公共资源仍需在接入资源 owner/organization 字段后复用同一边界；官网/赛事业务保持排除。

### 并发撤权验收及操作审计查询

- concurrency-smoke 实际观察 pg_locks 中管理写请求等待，持锁期间撤销临时账号权限后提交，等待请求返回 403，数据版本和审计均未改变。两个同版本并发修改结果为 200/409，仅一次版本递增和审计。
- V7 增加全局审计读取权限及索引；审计分页/搜索接口与只读页面接入，复用 ResourceTable 和八语言文案。需该权限的全部范围，普通账号无权读取。
- audit-smoke 验证 105 条分页、无重复、搜索、字段、参数边界、匿名与未授权拒绝、禁止 DELETE；浏览器显示真实操作记录。
- Maven verify、TypeScript、8 项前端测试通过；并发与审计脚本加入 CI，未运行远端 CI。
- 审计当前覆盖已记录的用户、角色和组织写操作；登录安全事件、修改前后差异、保留策略和其他公共模块的审计仍待补充。

### 本人登录会话

- 新增本人会话分页、退出指定/其他/当前会话接口与账户菜单抽屉。只返回独立记录标识，不返回 session_id 或 Cookie；当前会话优先展示。
- 撤销操作写入审计，当前会话撤销后清理安全上下文；接口按登录用户名限定，不允许跨账号操作。
- sessions-smoke 临时双账号验证记录字段、当前标记、跨账号隔离、指定/其他/当前撤销及重放拒绝通过。Maven verify、TypeScript、8 项前端测试通过；脚本加入 CI。
- 浏览器确认账户入口、会话列表、确认弹窗及取消；未退出用户现有会话。共享确认弹层修复抽屉内部无障碍挂载。
- 尚无设备/IP 识别展示，也未实现管理员在线会话管理；不能视为完整登录安全交付。

### Login rate limiting (2026-09-09)

- V8 adds shared PostgreSQL minute buckets with atomic increments, account/source quotas, hashed keys and scheduled expiry cleanup. A denied attempt commits the counter and returns 429 with Retry-After.
- The filter runs after CSRF and before password authentication; oversized passwords are rejected before hashing. Client-supplied forwarding headers are ignored. Eight-language UI feedback is wired.
- Defaults: 30 attempts/account/minute and 120 attempts/connection-source/minute. Configure LUMIRA_LOGIN_ACCOUNT_PER_MINUTE and LUMIRA_LOGIN_SOURCE_PER_MINUTE.
- Real HTTP tests passed for blocked correct passwords, no session creation, retry response, committed counters, elapsed-window reset and oversized passwords. Existing authentication/pagination/revocation regression passed. Filter unit tests cover spoofed forwarding headers, oversized input and non-login requests.
- Deployment gap: the production edge proxy may aggregate all requests under one backend source address. Trusted client-address forwarding and capacity validation remain required before production. Security-event auditing and account recovery remain incomplete.

### Trusted proxy forwarding and current audit

- Added exact-IP trusted proxy configuration and a dedicated client-address header. Untrusted peers, malformed or repeated headers fall back to the connection address. Four resolver tests pass.
- Compose adds a Caddy edge that forwards API requests directly to the server and overwrites the dedicated header. The server trusts only the configured edge address. Local development defaults to no trusted proxies.
- Current TypeScript, 8 frontend tests, 14 Java tests, Maven verify and frontend production build pass. Restarted local services; login-limit, data-scope and sessions HTTP suites pass.
- Compose config parsing passes. Docker daemon is unavailable locally; Caddy runtime validation and the real proxy chain remain unverified. This loopback HTTP topology is not proof of production TLS, capacity or high availability.
- Updated current-state-audit.md to distinguish implemented features, previous evidence and outstanding platform scope.

### Shared route registration

- Added one typed route registry for labels, read permissions, server section validation and safe login return paths. Tree and dual navigation share the same group metadata, including the secondary system-settings group.
- Workspace authorization and titles consume the registry; feature rendering uses an exhaustive typed mapping so a newly registered page cannot silently fall back to the users page.
- Added navigation coverage/uniqueness, empty-group filtering and permission isolation checks. All 10 frontend tests pass. Browser roles page loads with the correct title and controls. This does not replace the outstanding full navigation interaction regression.
- The page components and module capabilities remain code-defined, while V42 now provides a server-owned versioned menu tree, runtime permission filtering and an admin editor. Plugin-backed component registration remains outstanding.

### Organization hierarchy browsing

- Organization lists now accept an optional parent filter: root, a parent UUID, or omitted for the existing all-organizations behavior. Filtering and totals share the same SQL predicate; pagination remains server-side.
- Added an ordered ancestor endpoint protected by organization read permission. The page supports drilling into child organizations, breadcrumb navigation, all-organizations search and defaulting new records to the current parent; existing table, form and selection components are reused. Labels cover eight locales.
- Maven verify, TypeScript and 10 frontend tests pass. Expanded organizations-smoke passes root/child filters, page totals, ancestor ordering, invalid/missing parent rejection and existing write/membership/cycle/version tests. The test exposed a missing GET security matcher, which was fixed before rerunning successfully.
- Browser temporary parent/child records verified drill-down and breadcrumb display, then returned to root. Fixtures were removed. Refresh currently returns hierarchy selection to root; deep-link persistence and the remaining platform migration scope are not complete.

### Basic personal profile

- Compared the legacy ProfileController/BasicInfoUpdateRequest. Added V9 for real name, birth month and region; self-service display-name editing shares the user version with administrative edits. The account identity is resolved from authentication, never from submitted IDs or usernames.
- Added ProfileRepository/Service and GET/PUT account/profile. Writes lock and recheck the enabled account, validate lengths/months, reject stale versions and append a profile.update audit event in the same transaction.
- The shared FormDrawer is used from both account menu locations; eight-language fields, read-only username, current-detail loading and session/users cache refresh are wired.
- Maven verify, TypeScript, 10 frontend tests and production build pass. profile-smoke passes ordinary-account writes, field/authority isolation, CSRF, stale versions, invalid-input rollback, refreshed identity and audit; CI includes the script but remote CI has not run. Browser verified the entry, populated fields and cancel without changing administrator data.
- Avatar upload, verified email/mobile binding, configurable extra fields and the rest of the legacy profile lifecycle remain outstanding and depend on file/message/configuration modules. This is not full profile migration completion.

### Dictionary administration foundation

- Compared the legacy SystemDictionaryManagementRepository: types, items, enabled lookups, hierarchical item values and import are separate requirements.
- V10 introduces dictionary types with unique stable codes, names, enabled state, FLAT/TREE structure metadata, remarks and versions. Controller/Service/Repository provide paginated search, details, create/update/delete using existing global write authorization, transaction serialization and audit.
- Added dictionary read/write permissions to initialization and role assignment, including eight-language permission labels. Runtime migration and Maven verify pass; TypeScript and all 10 frontend tests pass.
- dictionaries-smoke passes ordinary-account denial, create, duplicate conflict, search and paging bounds, validation rollback, disable, stale update/delete and audit. The script is included in CI; remote CI has not run.
- Dictionary items, hierarchy enforcement, enabled lookup consumers, import/export and the management UI remain to be migrated. Structure metadata alone does not implement tree dictionaries; no completion claim for this module yet.

### Dictionary type management page

- Registered dictionaries in the shared route/navigation definition and exhaustive workspace renderer. Read and write actions are distinct; details reload before editing. Reuses ResourceTable, FormDrawer and DeleteAction, with eight-language fields, validation and audit labels.
- TypeScript, 10 frontend tests and production build pass. Browser created a temporary dictionary through the form, refreshed to verify persistence, then confirmed deletion and an empty list. The real audit records are retained.
- Type management is now accessible through the UI. Item management, tree behavior, import/export and lookup consumers remain outstanding.

### Dictionary item backend

- V11 adds items with dictionary-scoped unique values, labels, sorting, enabled state, remarks, versions and parent relations. Composite foreign keys prevent cross-dictionary parents. CRUD shares dictionary permissions, global write scope, transaction serialization and audit.
- Tree writes reject self/descendant cycles; flat dictionaries reject parents. Existing children block item deletion, items block dictionary deletion, and nested items block conversion to flat. Type-page failure messages are translated in eight languages.
- Maven verify, TypeScript and 10 frontend tests pass. Real dictionary-items-smoke covers uniqueness, cross-dictionary reads/parents, cycles, deletion guards, structure conversion, sorting/pagination/search and stale updates/deletes. Dictionary-type regression also passes. CI includes the new script.
- Item management UI, enabled-item lookup, imports/exports and full dictionary consumer integration remain outstanding.
- Fixed stop.mjs to wait for process-group termination before removing runtime state, after observing an immediate restart collide with the exiting backend on 8090. A timeout now preserves the process state for inspection/retry.

### Dictionary item management UI

- Type rows now open item management with paginated search, create/details/edit, sorting, enabled state, remarks and versioned delete. Reuses ResourceTable, FormDrawer and DeleteAction; TREE types expose a parent selector with off-page detail loading.
- ResourceSelect accepts a label field, allowing organization names and dictionary item labels to share paging/search and error behavior. Item errors and audit labels cover eight languages.
- TypeScript, all 10 frontend tests and production build pass. Browser created a root and child, selected the parent by its displayed label, and submitted both. HTTP confirmed the persisted parent ID; temporary records were deleted through versioned APIs, preserving audit evidence.
- The current item list is flat with parent editing. Tree presentation, deep links, enabled lookup consumers and import/export remain outstanding; the dictionary module is not yet complete.

### Enabled dictionary lookup

- Added authenticated read-only GET /api/dictionaries/{code}/items and item detail lookup. It exposes only id, parentId, value, label and sortOrder; dictionary management permissions remain separate. Disabled/missing dictionary or unavailable item detail returns 404.
- A recursive enabled-tree query runs before search/pagination. Disabled ancestors remove their entire subtree from candidates. List/count share a repeatable-read snapshot; there is no cross-request cache delaying disable changes.
- Added DictionarySelect as a thin ResourceSelect adapter for stable item UUIDs, remote search/pagination and off-page selection labels. Consuming backend writes must still resolve the selected ID against the expected dictionary and current enabled state; UI filtering alone is not validation.
- Maven verify and TypeScript pass. dictionary-lookup-smoke verifies an ordinary account, minimal fields, page/search behavior, ancestor/item/type disable and mutation denial; item CRUD regression also passes. CI includes the new smoke script.
- The adapter has not yet been mounted into a migrated configurable profile field. Tree presentation, deep links, import/export and full consumer integration remain outstanding.

### URL-backed list and hierarchy state

- Added shared list-location parsing and useListLocation/useLocationParams hooks. Organization parent, dictionary item context, paging and searches now use query parameters; item paging/search keys are separate from the containing dictionary list. Native history integration uses Umi history and keeps the workspace shell mounted.
- UUID and numeric query inputs are validated before API use. Invalid values recover to defaults. Missing/inaccessible dictionary details show an error and a return action. ResourceTable synchronizes its input when the applied search changes externally.
- TypeScript, the prior 10 tests, the added parser test and production build pass. Browser direct-link and refresh both restore the selected dictionary and search text. The fixture was removed. Browser back/forward and organization deep-link combinations still need fuller regression.

### Dictionary hierarchy browsing

- Item listing supports root/direct-parent filtering while omitted parent preserves all-item search. Ancestor lookup is dictionary-scoped and read-authorized. Organization and dictionary services share ParentFilter parsing.
- TREE item pages default to root items, support descending by label and breadcrumb return, and persist parent selection in items.parent. New items default to the current parent. The all-item list remains available.
- Maven verify, TypeScript and all 11 frontend tests pass. Expanded dictionary-item HTTP regression covers root/child filters, ancestor ordering and cross-dictionary rejection; the organization suite also passes after shared parsing changes.
- Browser opened a parent deep link, confirmed only its child plus the ancestor breadcrumb, and returned to root. Temporary fixtures were removed. Import/export and the remaining platform scope are still incomplete.

### Versioned dictionary dataset transfer

- Compared legacy DictionaryImportService: its 20,000-row/20-MB limits, TXT/XLS/XLSX parsing, preview and file hash are migration requirements, not replaced by the new transfer format.
- Added schemaVersion=1 JSON export/import preserving code/name/status/structure/remarks and item values, labels, parent values, sort and status. Import creates a new dictionary and refuses duplicate codes; no existing data is overwritten. Exports do not depend on the current UI page/filter.
- Iterative topology validation rejects missing parents, duplicates and cycles before writing; a 20,000-item limit returns an explicit error. Parent-first JDBC batches execute inside one transaction with dictionary global-write authorization and import audit. Export uses a repeatable-read snapshot and read permission.
- Maven verify (17 tests) passes, including a reversed 20,000-node chain and malformed datasets. Real dictionary-dataset-smoke verifies shuffled input, exact export/reimport preservation, duplicate rejection and no partial type for invalid datasets. CI includes the script.
- JSON transfer UI, legacy TXT/XLS/XLSX parsing, preview/hash confirmation and file-byte limits remain outstanding. The dictionary import/export workflow is not yet fully migrated.

### JSON transfer UI and preview

- Added import-file selection, server-side metadata/topology preview and explicit save through FormDrawer. Preview results retain the exact parsed payload; replacing a file/closing ignores stale asynchronous results. Browser selection enforces 20 MB and the service enforces 20,000 items. Export uses the full dataset API and a shared JSON download helper.
- The preview endpoint checks metadata, topology and duplicate code without writing. Invalid JSON now returns a direct 400 ProblemDetail instead of passing through the denied error route.
- Maven verify, TypeScript, 11 frontend tests and production build pass. Dataset HTTP regression now includes successful non-writing preview and malformed JSON 400, in addition to export/reimport. Browser checked import entry and disabled save before validation; full file-picker/download interaction remains unverified.
- Stop polling now tolerates EPERM as a still-running state within the existing timeout; observed exiting processes/ports were checked before retry. Local restart succeeded.
- Remaining transfer scope: backend byte limits, complete browser file flow, TXT/XLS/XLSX support, legacy preview/hash workflow and large-file capacity verification. JSON UI is not full transfer migration completion.

### Server-side dataset byte limits

- Added reusable BoundedBody annotation/advice for explicit per-endpoint budgets before JSON deserialization. Import and preview accept at most 20 MiB; the reader consumes no more than limit+1 bytes and verifies actual data as well as declared length. Three boundary tests cover exact limits, missing/underreported lengths and declared excess.
- Real testing exposed Next's default 10 MB request buffering truncating valid imports and causing timeouts. Configured proxyClientMaxBodySize to 21 MB so the backend receives the supported body and can return its 20 MiB limit error. The reader also avoids rejecting the tested one-byte excess before upload completion.
- Maven verify (20 tests) and frontend typecheck pass. body-limit-smoke passes exact 20 MiB preview, oversized import/preview 413, directly framed chunked overflow 413 and no writes. urllib chunked transport behaved differently from explicit http.client framing; the final direct test uses the latter. Dataset roundtrip regression passes. CI includes the boundary script.
- Remaining: full browser file flow, TXT/XLS/XLSX and legacy preview/hash adaptation, production ingress verification and concurrent large-upload capacity checks. This is not production capacity acceptance.


### Legacy dictionary file parser

- Added DictionaryFileParser for BOM-aware UTF-8 tab-separated TXT and first-sheet XLS/XLSX. Apache POI 5.5.1 is the stable release listed at https://poi.apache.org/download.cgi when checked.
- Preserves legacy status, sorting defaults, level/leaf validation and flat-dictionary hierarchy normalization. Returns immutable dataset entries plus SHA-256 of original bytes; shared dataset topology validation remains authoritative.
- Rejects malformed encoding/workbooks, unsupported extensions, duplicate headers/values, invalid status/hierarchy, over-20-MiB files and over-20,000 rows. Workbook parsing retains POI defaults; compressed-workbook memory capacity has not been load-tested.
- Maven verify passes: 24 tests total, including equivalent TXT/XLS/XLSX results, hash changes, malformed inputs, hierarchy and byte limits.
- This parser is not yet exposed through the import endpoint or UI. Preview row errors, file hash confirmation, endpoint authorization/body tests and full browser file workflow remain outstanding. No runtime restart was needed for this isolated parser step.


### Legacy file upload endpoints

- Added dictionary file-preview/file-import endpoints accepting bounded raw binary uploads and type metadata. Both use dictionary write authorization and CSRF; final writes reuse the existing global-scope guard, transaction, uniqueness and audit flow.
- Preview returns a SHA-256 digest, validated summary and at most 20 rows without writing. Import reparses the actual bytes and rejects a mismatched expected digest before database writes. This detects file changes; the digest is not an authorization token or server-issued proof of preview.
- Maven verify passes. Local services restarted successfully. Real file HTTP smoke passes non-writing preview, changed/mismatched file rejection, CSRF and anonymous denial, parent preservation and duplicate conflict. Added to CI; remote execution and complete UI file flow remain unverified.
- XLS/XLSX parser equivalence was unit-tested previously; this HTTP smoke uploads TXT. Detailed per-row error previews and frontend metadata/file selection remain outstanding.


### Legacy file import UI

- Added TXT/Excel import entry with metadata, file selection, server preview, first-20-row table and hash-confirmed save. Reuses FormDrawer and the shared API/CSRF/error transport; binary upload support is shared in client.ts.
- Changing metadata or the file invalidates the preview and ignores stale responses. Save uses the immutable File and metadata captured with the preview. File selection enforces 20 MiB before upload.
- TypeScript and 11 frontend tests pass. Browser confirms the entry, metadata controls and disabled preview/save before file selection. Eight locales have preview action text; corrected Spanish/German placement after checking locale order.
- Actual browser file selection through save/download, detailed row-error reporting, shared metadata-field extraction and production build remain outstanding. This is not full import workflow acceptance.


### Shared dictionary form contract

- Extracted DictionaryFields, DictionaryMetadata and dictionaryDefaults for both dictionary editing and legacy file import. Code/name validation, structure, remarks and enabled controls now have one source; existing codes remain read-only.
- Preview generation is captured before asynchronous form validation. A close or metadata/file change during validation prevents the old action from uploading or publishing a stale preview.
- TypeScript and 11 frontend tests pass. Full browser file workflow and detailed import row errors remain outstanding; this refactor is not a complete platform migration claim.


### Structured file validation errors

- File parsing now accumulates row/field/reason diagnostics for invalid values, duplicate entries and invalid parent levels/leaf flags. Original physical row numbers survive skipped blank rows. At most 100 diagnostics are returned, without raw cell values.
- Shared ImportValidationException and ProblemDetail handling expose structured issues. ApiError preserves validated issue arrays, and the file import drawer displays a paginated list with localized invalid-value, duplicate and hierarchy reasons.
- Maven verify passes (25 tests), including original row positions and error cap. TypeScript and 11 frontend tests pass after adding eight-language reason labels. Corrupt files and malformed headers still return file-level errors.
- Full browser file workflow, success/invalid row counts and production upload capacity remain outstanding; no full migration completion claim.


### Browser file import and download acceptance

- Added scripts/dictionary-browser-smoke.mjs using a separate headless browser context and temporary dictionary. It authenticates via the actual login API and exercises UI file input, metadata fields, preview, save and download. Fixtures are removed after each run.
- Verified invalid TXT shows itemLabel diagnostics and disables save; replacing the file succeeds; metadata edits invalidate the preview; revalidation and save persist the new name and both items; JSON download contains those values; reload retains the dictionary. No document horizontal overflow at 1213x886.
- Both the initial happy path and expanded invalid-file run passed. This proves the TXT browser flow and JSON download, not XLS/XLSX browser selection or mobile navigation.
- The local script currently resolves Playwright from the legacy UI tooling, overridable by PLAYWRIGHT_PACKAGE. Default cached browser revision was missing; BROWSER_EXECUTABLE selected the installed chromium_headless_shell-1234 executable. It does not import legacy application code. Standalone scaffold browser-test dependency/CI setup remains outstanding.
- Structured error HTTP regression also passed after the previous backend restart. Remaining dictionary transfer scope includes preview row counts, full Excel browser flow and concurrent/production upload capacity.


### Standalone browser tooling and Excel acceptance

- Scaffold now owns pinned @playwright/test 1.60.0 and test:browser; browser tests no longer resolve legacy UI dependencies. The lockfile is updated. CI installs Chromium with system dependencies and invokes the browser suite after HTTP tests.
- Existing POI equivalence tests emit their verified XLS/XLSX workbooks into backend/target/browser-fixtures for reuse. Maven verify passes.
- Local browser suite passes TXT, XLS and XLSX: invalid file feedback/save denial, replacement, preview, metadata invalidation, import, JSON download, refresh and desktop width. Excel runs select TREE and verify the exported child references its root. Temporary dictionaries are removed after each format.
- Local invocation used BROWSER_EXECUTABLE pointing to installed Chromium revision 1234. CI will install its package-matched Chromium; remote CI has not executed. Run Maven verify before test:browser to generate fixtures, with the local services running.
- Remaining transfer details include complete valid/invalid row counts and upload-capacity validation. Other public platform migration scope remains active.


### Versioned public configuration foundation

- Compared legacy BrandingSettingsRequest and WatermarkSettingsRequest. Branding metadata and maintenance policy will be distinct sections so saves cannot unintentionally change access policy.
- V12 adds current setting and immutable revision snapshots. SettingStore reuses the transaction administration lock, post-lock global write authorization and audit. Each save checks expected version and stores payload, actor and reason atomically.
- Added typed Branding validation and GET/PUT admin settings/branding, with separate read/write permissions, bounded request size and URL validation. Covers names, image URLs, help/GitHub links and copyright/filing fields. Defaults apply before first write. Permissions are bootstrapped and labelled in role editing.
- Maven verify (28 tests), TypeScript and V12 local migration pass. settings-smoke verifies default read, anonymous/CSRF denial, save, stale version conflict, invalid URL/reason rollback, two historical snapshots and audit. Restored untouched defaults by removing only its newly created fixture. CI includes the script; remote run is unverified.
- This API is not yet connected to branding UI or public shell consumption. History read/rollback, maintenance policy, agreements, watermark and all other public platform scope remain outstanding. No deployment or full configuration completion claim.


### Branding configuration UI

- Added branding to the shared route/permission registry and both navigation layouts. The page covers the typed branding API fields and eight locales.
- Introduced SettingsSection: each section owns its form, captured version, change reason and save. Background query updates cannot replace an editor's unsaved fields. Successful saves update the cache/version; conflict errors preserve input.
- TypeScript and 11 frontend tests pass. New independent browser smoke verifies save, reload persistence and two-editor stale version rejection without losing the stale editor's text. Temporary branding was removed to restore untouched defaults. test:browser includes the new suite for CI; remote CI remains unverified.
- Public login/header/footer consumption, history/rollback, maintenance and other configuration sections remain outstanding. Saving branding does not yet change the site's displayed identity.


### Public branding consumption

- Added anonymous GET public/branding with only typed public fields; administrative version/history remain separate. Admin and public readers share BrandingReader.
- Root server rendering fetches branding with request-scoped deduplication, a bounded timeout and safe default fallback. Dynamic metadata uses configured name/favicon. BrandingProvider shares current public data with login welcome/background/footer, enabled external links and workspace name/logo; successful branding saves invalidate its query.
- Maven verify, TypeScript, 11 frontend tests and production build pass. Restarted local services. Browser verifies public API without login, configured login heading/document title, immediate header name update, existing save/refresh and stale-edit behavior. Fixtures removed.
- Logo/background/favicon asset rendering, outbound link targets, footer variations and backend-outage visual fallback are implemented but not all browser-verified. Tab metadata updates on server navigation/reload; cross-tab configuration propagation is not yet implemented. History/rollback and remaining configuration sections remain outstanding.


### Configuration history and rollback API

- SettingStore now provides repeatable-read paginated revision metadata and section-scoped snapshot lookup. Historical payloads are returned only by the typed admin endpoint, never the public branding endpoint.
- V13 links rollback revisions to their source version. Branding rollback revalidates the typed historical data and uses the shared global write guard, expected-version check and transactional revision/audit save. Existing historical records remain unchanged.
- Maven verify passes. Extended settings-smoke covers history pagination/detail, missing revisions, rollback producing version 3 from version 1, stale rollback denial and retained history. Runtime result is recorded separately below.
- UI history/rollback, read-only-role regression and other configuration sections remain outstanding.

- Runtime: V13 migration and restarted local service passed; expanded settings HTTP suite passed, including revision source and stale rollback assertions. Temporary setting/history/audit fixtures removed.


### Configuration history UI

- SettingsHistory is shared by SettingsSection: paginated history uses ResourceTable, detail uses the existing section fields in a disabled form, and rollback uses FormDrawer with a required reason and explicit replacement warning. Current-version rollback is disabled.
- Successful rollback updates the current editor, captured version, query cache and public branding. Historic form IDs are namespaced to avoid collisions with the open editor. Read-only users can inspect but cannot submit rollback.
- TypeScript and 11 frontend tests pass. Expanded browser suite creates two revisions, opens the first history detail, rolls it back, verifies new version 3 and restored editor text; existing anonymous branding/save/conflict checks still pass. Fixtures removed.
- History metadata includes actor/reason/version; timestamps and source version are available in the API but not yet presented in the UI. Read-only-role browser regression and remaining public configuration scope remain outstanding.


### Agreement configuration and public viewing

- Compared legacy agreement DTO/service and login visibility checks. Added user/privacy Markdown as an independent setting section using the existing versioned store, write scope, audit, history and rollback mechanisms. Admin endpoints require settings permissions; public GET exposes only the two texts. Payloads are bounded to 1 MiB and each text to 100,000 characters; empty strings hide their links.
- Added eight-language agreement settings page using SettingsSection, plus login links and read-only dialogs. Shared MarkdownContent uses react-markdown 10.1.0 (npm registry version checked), skips raw HTML, keeps the default safe URL transform and supports responsive images. It is not a legal consent recording mechanism.
- Maven verify, TypeScript and 11 frontend tests pass. Restarted local services. Browser smoke passes admin form save, anonymous user/privacy links, Markdown headings/emphasis, and exclusion of script execution/javascript URLs. Temporary config/audit/history removed. test:browser now includes this suite; remote CI unverified.
- Agreement-specific rollback/read-only-role/size-boundary regression, request-failure UI and richer Markdown parity remain to be checked. Maintenance, watermark and other public platform migration scope remains outstanding.


### Watermark configuration foundation

- Compared legacy watermark request, template validator and TEXT/IMAGE UI. Added typed settings for fixed/personalized lines, image URL, font/color/weight, rotation, spacing, offsets, stacking and opacity. Allowed legacy variables are username/nickname/mobile/email/realName/userId; malformed/unknown tokens are rejected.
- Added settings read/write/history/rollback and public configuration read using the existing versioned store and security guards. Shared SettingUrls now validates both branding and watermark URLs. Template resolution treats account text literally and falls back to fixed visitor lines when personalized output is empty.
- Maven verify passes (31 tests), covering template escaping/fallback and invalid values. HTTP smoke added to CI; runtime result follows below.
- UI controls, actual Ant Design watermark rendering and current-account variable supply are not yet wired. Verified email/mobile values depend on the remaining profile/channel migration. This is not full watermark completion.

- Runtime watermark HTTP smoke passes: CSRF/auth, template/URL/numeric rejection, stale versions, public read and unchanged revision count after rejected edits. Fixtures removed.
- Observed a slow backend startup (~130 seconds) killed by start.mjs after its 120-second observation deadline. Fixed timeout handling to retain running services/state instead of sending SIGTERM. Confirmed the previous processes were terminal before restarting; new startup and watermark smoke passed. The timeout-retention branch is code-reviewed, not yet exercised with an intentionally delayed service.


### Watermark UI and runtime

- Registered an eight-language watermark settings page using SettingsSection/history. Controls cover text/image modes, fixed and personalized lines, image URL, font, rotation, spacing, offsets, stacking and opacity.
- PlatformWatermark uses native Ant Design Watermark in a fixed pointer-transparent layer. It stays separate from page children so toggling does not remount feature state. Shared profile data supplies username/display name/real name/UUID; missing email/mobile values remain empty until those profile capabilities migrate.
- All section saves/rollbacks now invalidate their corresponding public query, including agreements and watermark. Profile writes already invalidate the same profile query used by the watermark.
- TypeScript and 11 frontend tests pass. Browser verifies form save, canvas rendering of QA admin from the username template, persistence after reload, actual underlying control clicks and immediate disable. Temporary settings removed; browser suite added to CI command.
- Image loading/opacity visual regression, read-only-role tests, mobile/modal layering and full profile variable migration remain outstanding. Watermarks are a presentation feature, not a tamper-proof security boundary.


### Maintenance login policy

- Compared legacy MaintenanceLoginPolicyService. Added an independent maintenance setting with enabled/title/message/endAt/allowedRoleIds, version/history/rollback and public status excluding role IDs. Role references are validated while holding the administration transaction lock; disabled/missing roles cannot be configured.
- Successful credential authentication now checks the current maintenance policy. Disallowed accounts receive 503 maintenanceActive and their newly saved session/context is invalidated. Enabled allowlisted roles may log in; global settings writers retain a recovery path. An elapsed endAt disables enforcement automatically.
- Added AdministrationGuard.hasGlobal for shared policy checks and eight-language login errors. Existing authenticated sessions are not forcibly revoked; this migrates the legacy login policy, not a system-wide traffic shutdown.
- Maven verify (33 tests), TypeScript and 11 frontend tests pass. Real maintenance-smoke passes session-clearing denial, global admin entry, allowed role, disabled role denial and expired-window login. Temporary policy/user/role fixtures removed. CI includes the suite; remote execution unverified.
- Maintenance editing UI, public status banner, full administrative delegation/lifecycle recovery guarantees and rollback-specific tests remain outstanding. Other platform migration scope stays active.


### Maintenance settings and notice UI

- Added eight-language maintenance route using SettingsSection/history. Local datetime input converts to an ISO instant and restores browser-local time on read. Empty end time means no scheduled end.
- Added reusable ResourcePicker for paginated multi-selection, preserving selected IDs across page/search changes, respecting disabled forms and allowing removal of previously selected disabled resources. Role discovery uses existing role-read authorization; settings-only administrators without that permission cannot browse roles yet.
- Login MaintenanceNotice polls public status every 30 seconds and shows configured title/message/end time while active. Login enforcement remains server-authoritative.
- TypeScript and 11 frontend tests pass. Browser suite verifies enable/save, exact local-time roundtrip, anonymous notice, refreshed editor and disable. Temporary maintenance records removed; test:browser includes this suite.
- Multi-page role picker browser regression, role-read permission ergonomics, notice request-failure behavior and full administrative lifecycle recovery guarantees remain outstanding, alongside broader public-platform migration.


### Maintenance role lookup permission boundary

- Replaced role-management lookup with a settings-read-authorized, paginated maintenance role endpoint returning only id/name/enabled. List/count use a repeatable-read snapshot and literal substring search. No role authorities or administrative mutation access is exposed.
- Maven verify and frontend typecheck pass. Expanded HTTP smoke verifies no-permission denial, immediate settings-read grant, minimal fields, search/count/page bounds, continued role-management denial and settings-write denial.
- Browser smoke creates 12 temporary roles, searches the picker, selects records on two pages, returns to page one to confirm retained selection and verifies both IDs saved. Existing maintenance time/notice/disable regression passes. All temporary policy, role, user and audit fixtures removed.
- This closes the settings-only role-discovery gap. Read-only visual history/picker behavior, admin lifecycle recovery and broader migration requirements remain outstanding.


### Configuration recovery administrator guard

- V14 defines one shared recovery-capability view and predicates used by user disable and role update/assignment protection. Settings recovery requires both global settings write and effective settings read, alongside existing user/role administrator requirements. Existing administration transaction locking and rollback preserve rejected mutations.
- Restarted the confirmed local scaffold processes with the verified build; V14 was applied. Recovery SQL smoke passes with all fixtures inside a rolled-back transaction using the same administration advisory lock. Covers missing read permission, disabled account, global versus SELF role scope, disabled role, and redundant administrators so the guard does not overprotect when another recovery account exists.
- User administration, role lifecycle/grant/revoke and maintenance login-policy HTTP regression all pass against the updated runtime. The SQL smoke is included in CI; remote CI has not run. Browser coverage of every last-administrator mutation combination remains outstanding.
- Updated current-state-audit.md to remove the resolved V13/runtime discrepancy. Full public-platform migration remains active; business migration remains excluded.


### Shared configuration reload and history metadata

- SettingsSection now offers an explicit confirmed reload for all configuration sections. It fetches before replacing fields, clears validation/reason on success, adopts the current server version and refreshes public consumption. Cancellation or a failed fetch leaves edits intact. Pending operations disable competing form/history actions. Background reads still do not replace unsaved edits.
- SettingsHistory renders locale-formatted timestamps with machine-readable time elements and the source revision of a rollback. Added matching translations for all eight locales.
- TypeScript and 11 frontend tests pass. Extended branding browser regression passes cancellation, injected HTTP 503 with preserved edits, successful fresh reload and cleared reason, history timestamp/source column display, plus existing save/conflict/public identity/history rollback tests. Temporary settings were removed. The browser exercise uses the shared component through branding; all-section read-only and mobile regression remains outstanding.


### Public configuration read failures

- Added shared QueryFailure with localized warning and disabled/loading retry action. AgreementLinks and MaintenanceNotice now surface read errors instead of silently disappearing, pass query abort signals to the API client, and retain last successful content when a refresh fails. A successful inactive maintenance response removes the old notice. Server login policy remains authoritative.
- Added error text in all eight locales. TypeScript and 11 frontend tests pass. New isolated anonymous browser suite intercepts only its own requests to exercise HTTP 503, successful retry, agreement dialog content, a real 30-second maintenance polling failure retaining the previous notice, then inactive recovery clearing it. Desktop document overflow check passes. It changes no server configuration and is included in test:browser/CI; remote execution remains unverified.
- Slow initial loading, full mobile error-state layout and other public consumers still need broader regression. Full public-platform migration remains active.


### Read-only configuration and picker regression

- Added an isolated settings-read-only account browser suite across branding, agreements, watermark and maintenance. It checks disabled editor fields/save, historical snapshot fields/reason/rollback, and real direct PUT/rollback 403 responses without version changes. Fixtures preserve pre-existing settings and remove only newly created defaults, test user/session and test role.
- Found ResourcePicker inherited form disabled state for browsing controls as well as selection. It now enables local search/refresh/paging within a nested ConfigProvider while retaining the parent disabled flag for every checkbox and guarding selection callbacks. This behavior is shared by normal editors and historical snapshots.
- TypeScript passes. Read-only browser suite verifies searching a fixture role while its checkbox remains disabled. Existing maintenance browser suite still passes writable search/cross-page selection/persistence, notice/time/save/disable. CI browser command includes the read-only suite; remote execution remains unverified.
- This covers the current four settings sections, not all restricted-administrator delegation, mobile layouts or future configuration types. Full public-platform migration remains active.


### Global write capability UI alignment

- Current-user API now exposes globalAuthorities from the same platform_global_authority view used for server scope checks, queried on each request for the enabled account. Existing authorities remain available for reads and resource-scoped user editing. No client capability bypasses server authorization.
- Workspace uses global grants for configuration/dictionary/organization/role mutations and role/organization assignments. UsersPage separates canCreate (global) from canWrite (resource scope), preventing misleading create controls without removing scoped edits. Missing capability data fails closed in the UI.
- Maven verify, TypeScript and 11 frontend tests pass; local services restarted with the new API. Expanded browser suite uses a SELF role with write grants: all four settings remain disabled, direct writes/rollback return 403, and global create controls on users/roles/organizations/dictionaries are absent. Promoting the role to ALL enables settings; reverting to SELF disables them after reload, on the same session. Temporary fixtures removed.
- Data-scope HTTP regression passes SELF/ORGANIZATION/SUBTREE/ALL, immediate changes, foreign-write rejection and allowed own-user edits. UI background permission changes are reflected when current-user data is refreshed, not pushed in real time. Full platform migration and remaining delegation regression stay active.


### Configurable profile field schema foundation

- Compared legacy ProfileFieldSettingItem/ProfileFieldSettingVO and SystemProfileSettingsAppService: visibility, required state, weight, groups, sort order and TEXT/TEXTAREA/NUMBER/DATE/SELECT definitions must be retained. Added typed ProfileFields schema for those properties with explicit choice options. Current built-ins match the migrated profile (displayName, realName, birthDate, region); display name remains required/visible. Reserved identity/security keys cannot become custom fields.
- Validates bounded schema/labels/groups/options, unique stable keys, built-in type protection, consistent group labels, positive weights and valid types. Defaults and every custom type plus invalid keys/options/limits have four Java tests. Email/mobile/avatar and legacy nickname mapping still need the remaining profile migration. Dictionary-backed choices are not yet connected.
- Added admin read/save/history/detail/rollback and authenticated account schema read. Reuses SettingStore for global write scope, transactional version/revision/audit and source-linked rollback; request sizes and security route permissions are explicit. No anonymous schema endpoint.
- Maven verify passes (37 tests), rebuilt/restarted local services, real profile-fields HTTP smoke passes defaults, auth denial, account schema read, save, invalid/reserved field rejection, stale writes and history/rollback. Temporary schema revisions/audit removed; script added to CI (remote run unverified).
- Schema management UI, profile extra-value persistence/validation/rendering, dictionary consumers and completion weighting remain outstanding. This is the backend definition foundation, not completed dynamic profile functionality. Full public-platform scope stays active.


### Profile field schema management UI

- Registered profile-fields in the shared permission/navigation registry and Workspace. The page uses SettingsSection and its history/rollback/reload behavior, with a reusable field-list/editor for current and historical forms. All controls have eight-locale labels.
- Supports custom field add/remove, labels/descriptions/placeholders, five types, SELECT options, visibility/required state, completion weight, grouping and order. Built-in identifiers/types and required visible displayName are locked. Custom group names reuse the existing group label when adding fields across locale changes.
- Browser found and fixed missing unregistered metadata in Form.List: preserved watching and explicit hidden custom/options registration keep schema metadata in submitted values. Backend validation remains strict.
- TypeScript and 11 frontend tests pass. Browser verifies built-in protection, custom SELECT creation/options, save and reload, custom removal and historical rollback to a new version. Temporary schema/history/audit removed; suite included in CI command, remote CI unverified.
- Account form rendering/persistence of custom values, dictionary linkage, completion scoring and wider read-only/mobile/100-field performance regression remain outstanding. The management interface does not yet make custom fields editable in the personal profile.


### Account custom profile value persistence

- V15 adds JSONB custom values to platform_user. Added authenticated account profile-values read/write endpoints; actor identity always comes from Authentication, with no user-id write parameter. Responses contain current visible custom definitions, values, shared user version and schema version in a repeatable-read snapshot.
- Writes share the administration advisory lock with schema updates, lock the account, require both captured versions, validate TEXT/TEXTAREA lengths, required values, decimal precision, calendar dates and SELECT membership, and commit value/version/audit atomically. Unknown/hidden field writes are rejected. Hidden/retired stored values are preserved but excluded from the current response, permitting later schema restoration. Basic profile writes preserve the new column.
- Maven verify passes (41 tests). Local V15 migration/restart complete. New HTTP smoke passes validation, account isolation, user/schema conflicts, hidden-field rejection, retained values following schema rollback and audit count; prior basic-profile HTTP suite also passes. Temporary schema/account/history/audit/session fixtures removed. CI includes new smoke; remote CI unverified.
- Personal profile UI custom-field rendering/save, built-in schema consumption (including birthDate/birthMonth alignment), dictionary-backed choices and weighted completion remain outstanding. Historical value retention needs a later lifecycle policy. This closes storage/API only, not the full configurable profile feature.


### Account custom profile form

- Added ProfileValuesDrawer from the profile entry, using FormDrawer, shared API/query utilities and one schema-driven field renderer for TEXT/TEXTAREA/NUMBER/DATE/SELECT. Visible fields are grouped, with labels/descriptions/placeholders and required validation; decimal inputs use stringMode. No-field and error states are localized in eight languages.
- Custom profile saves are independent of the basic profile form, use captured user/schema versions and invalidate relevant account/user queries after success. Opening custom fields prompts before discarding dirty basic edits. Reopening fetches fresh data; rejected saves preserve entered values.
- TypeScript and 11 frontend tests pass. Isolated browser fixture verifies all five controls, required validation, real save/reload persistence and schema revision conflict retaining unsaved text. Temporary user/session/schema/revisions/audit removed. Suite added to CI command; remote CI unverified.
- Built-in field configuration consumption, dictionary-backed options, completion scoring, more comprehensive dirty-switch and mobile/keyboard regression remain outstanding. Full public-platform migration remains active.


### Dictionary-backed custom profile choices

- Profile field SELECT definitions optionally bind an enabled dictionary code, mutually exclusive with static options. Null remains compatible with existing schemas; static field validation and history continue to work. Schema saves and rollbacks validate the dictionary under the administration transaction lock.
- Account custom value writes reuse DictionaryLookupService to validate the selected UUID against the enabled dictionary tree, including ancestors, while holding the same write lock. Unknown, foreign and disabled choices cannot be submitted; labels may change without changing stored identity.
- Profile schema editor exposes dictionary code with eight-language guidance and clears conflicting options. Profile value renderer reuses DictionarySelect/ResourceSelect for remote search and selected-label lookup.
- Maven verify passes (42 tests), TypeScript and 11 frontend tests pass. Restarted local service. Profile schema and expanded value HTTP regression pass, covering invalid UUID, disabled ancestor and disabled dictionary rejection. Expanded browser suite selects a real dictionary item and verifies persisted UUID alongside the five prior field controls and conflict retention. Fixtures removed.
- Dictionary deletion/reference lifecycle, binding editor-specific visual regression and built-in field/completion migration remain outstanding. Full public-platform migration stays active.


### Dictionary deletion reference protection

- DictionaryService now checks active profile schema references, including hidden fields, before deletion. Repository queries the versioned profile-fields JSON using the dictionary code; mutation uses the existing shared administration transaction lock, so it is serialized with schema binding writes. Historical-only bindings do not permanently prevent deletion: restoring such a revision revalidates dictionary availability and fails atomically when missing.
- Shared DeleteAction recognizes dictionaryReferenced and displays explicit eight-language unbind-first guidance. Existing dictionary-item and stale-version guards remain in place.
- Maven verify, TypeScript and 11 frontend tests pass; rebuilt/restarted local services. New real HTTP suite verifies hidden binding protection for an empty dictionary, unchanged data/audit after refusal, successful deletion after unbind, and failed historical restore preserving current schema. Existing dictionary CRUD/security/version/audit HTTP suite passes. Fixtures removed; new smoke added to CI, remote execution unverified.
- Individual dictionary item reference lifecycle, broader browser feedback verification, and the remaining public platform migration are still outstanding.


### Selected dictionary item lifecycle

- V16 adds indexed profile dictionary references with user cascade deletion and item RESTRICT. Backfill uses current and historical field definitions to recover retained dictionary selections, excluding unrelated string values. Profile value saves update references in the same transaction as values/version/audit, using set-based deletion/insertion; hidden/retired field references remain intact.
- Item deletion checks profile selection references and returns dictionaryItemReferenced; shared DeleteAction displays eight-language guidance. Changing or clearing a visible value releases its previous reference; hiding alone does not.
- Maven verify, TypeScript and 11 frontend tests pass. Local V16 applied after restart. Rollback-only migration test runs the migration SQL with isolated table/section identifiers and verifies current/history backfill, exclusion of unrelated text, FK refusal and user cascade. Adjusted test to recognize PostgreSQL RESTRICT violation as well as foreign-key violation.
- Expanded profile value HTTP smoke passes selected/hidden delete refusal and successful deletion after clearing, plus existing validation/isolation/version checks. Dictionary item regression passes hierarchy/CRUD/version behavior. Fixtures removed; migration test added to CI. Browser cleanup now deletes its fixture account before referenced items. Remote CI remains unverified.
- Built-in configurable profile fields, completion scoring, wider lifecycle UI, and the rest of the public platform migration remain outstanding.


### Built-in configurable profile fields

- Basic profile reads now include visible built-in field metadata and captured schema version in a repeatable-read snapshot. ProfileSchemaReader is shared by the schema controller, basic profile service and custom value service. Basic writes serialize with schema mutations, check account/schema versions, enforce configured required fields and reject changes to hidden fields while retaining their stored values. Body is bounded to 16 KiB. Legacy requests without schemaVersion remain accepted only with untouched schema version 0.
- ProfileDrawer renders configured groups, order, labels, hints, visibility and required state. Stable schema key birthDate maps explicitly to existing birthMonth storage and a month input; no fabricated day or destructive rename of historical definitions. Default labels remain translated, customized labels are displayed verbatim.
- Maven verify, TypeScript and 11 frontend tests pass. Restarted local service. Existing profile HTTP regression and new built-in HTTP suite pass required/hidden writes, retention, metadata order and schema conflicts. Expanded browser suite verifies a hidden real-name input, renamed required region, basic save, then existing custom/dictionary controls and schema conflict retention. Fixtures removed; built-in smoke added to CI, remote CI unverified.
- Completion scoring, full legacy avatar/email/mobile field coverage, lifecycle controls and other platform migration scope remain outstanding.


### Weighted profile completion

- Compared the legacy completion algorithm. Added a reusable pure calculator with visible-field weight totals, earned weight, rounded overall/group percentages, ordered groups and field completion metadata. Hidden fields do not contribute; zero-total remains 0. Three tests cover weighted rounding, group ordering and hidden/empty inputs.
- Authenticated completion endpoint evaluates the same profile/schema snapshot. Current type/option validation and enabled dictionary lookup determine whether custom values count, so invalidated old values are incomplete. Responses omit actual profile values.
- Both profile drawers reuse ProfileCompletion for total/group progress and a localized missing-field list. Basic/custom saves invalidate its query; reopen reads fresh state.
- Maven verify passes (45 tests), TypeScript and 11 frontend tests pass. Restarted local service. HTTP regression covers default and weighted scores, numeric zero, hidden exclusion and changed-type invalidation. Browser verifies 22% before custom completion and 89% after saving/reopening alongside existing controls and conflicts. Test locator was scoped to the active drawer to avoid matching the exiting drawer animation. Fixtures removed; HTTP suite added to CI, remote CI unverified.
- Full avatar/email/mobile completion, targeted missing-field navigation, large-schema read performance and other platform migration remain outstanding.


### Batched dictionary validation and completion reads

- DictionaryLookupRepository/Service now expose one enabledSelections batch operation. It deduplicates dictionary/item pairs, walks only requested ancestor chains, rejects disabled or mismatched dictionaries, requires a reachable root and stops revisiting cyclic ancestors. Empty requests skip SQL.
- Profile value writes and completion evaluation reuse this batch instead of per-field dictionary/find round trips. Completion reads reuse the already resolved account and schema and fetch stored values once. By code structure, dictionary validation uses one statement regardless of selected-field count; this is not measured throughput or a capacity claim.
- Maven verify passes; restarted local services. Expanded value HTTP regression saves two selections and verifies weighted score, then injects a cycle into isolated fixture data to confirm bounded rejection and incomplete scoring before restoring the fixture. Disabled ancestor, reference deletion and remaining validation still pass. Completion and existing dictionary lookup HTTP suites also pass. Fixtures removed.
- Load/latency benchmarking, transaction contention and the wider platform migration remain outstanding.

### Completion rounding boundary correction

- Overall and group completion share a calculation that reserves 100 percent for earned weight equal to total weight. Incomplete values retain rounded percentages capped at 99; empty totals remain 0.
- Added a unit regression for 200/201 weight and subsequent true completion, checking both overall and group results. Maven verify passes all 51 tests.
- Rebuilt/restarted local services. Expanded real HTTP completion regression verifies the same boundary through saved field configuration and profile values; existing zero-value, hidden-field, schema invalidation and response privacy checks pass. Fixture data was removed.
- File foundation, complete upload/permission/lifecycle facilities and the rest of the public-platform migration remain outstanding. Business migration remains excluded.

### File metadata and reserved storage identities

- V17 introduces owner-linked file metadata with UPLOADING/READY/DELETING states, size/digest consistency constraints, version and timestamps, owner listing and cleanup indexes. Account deletion retains orphan metadata for later physical cleanup.
- Internal FileRepository reserves metadata and exposes owner-constrained reads and version/state-guarded completion/deletion transitions. User deletion requests accept READY only; active upload cancellation and stale-upload recovery require the forthcoming coordinator/lease policy. Physical deletion must succeed before metadata removal.
- BlobStore accepts a pre-reserved UUID, keeping metadata and storage identity aligned. Its convenience overload generates UUIDs. Local publication cannot overwrite an existing object; regression verifies original bytes and staging cleanup after duplicate publication.
- Maven verify passes 52 tests. Rollback-only PostgreSQL migration test validates default state, incomplete READY rejection, size/digest constraints and account orphan retention. Included in CI, remote execution unverified. Local services rebuilt/restarted.
- This is an internal foundation, not a completed upload service. Endpoints, content validation, quotas, ACL integration, upload leases, cleanup/retry workers, production object storage, file UI and avatars remain outstanding. Repository transition behavior still needs service-level integration coverage.

### Owned file upload, retrieval and deletion API

- FileService reuses ProfileRepository, FileRepository, BlobStore and short TransactionTemplate boundaries. It reserves metadata before streaming (20 MiB maximum), commits READY metadata after publication, and avoids holding a database connection throughout the body transfer. Filenames are bounded and reject path separators/control characters; storage paths remain UUID-based.
- Authenticated, CSRF-protected raw octet-stream upload returns a created record. Metadata, attachment download and version-checked deletion resolve the account from Authentication and constrain lookup to its owner. Foreign and missing records return 404. Downloads use octet-stream, attachment, nosniff and no-store headers.
- Upload failures mark the reserved record DELETING before deleting bytes and metadata. Cleanup failure preserves metadata; an already READY record cannot be abandoned or have its bytes removed by this failure path. Failed user deletion can be retried with the current DELETING version. Stale upload recovery still needs leases and a worker.
- Local root is configurable with LUMIRA_FILES_LOCAL_ROOT, default .runtime/files. This adapter remains local/dev; production object storage and multi-replica operation are not established.
- Maven verify passes 56 tests including filename and cleanup ordering/failure guards. Rebuilt/restarted local services. Real HTTP suite passes uploads (including empty), exact digest and bytes, download headers, CSRF, anonymous and foreign-account denial, stale deletion and final absence. The header-only oversize test targets backend 8090 directly and verifies early 413; a full oversized urllib write through 8010 hit Broken pipe on early rejection and is not claimed as a passing proxy UX test. Fixtures removed.
- Added HTTP suite to CI and restored explicit PSQL_BIN for the preceding migration suite. Remote CI unverified. Mockito currently emits a dynamic agent warning in local tests.
- File listing/UI, content-specific validation and avatars, quota/rate limits, audit events, upload leases, retries/orphan cleanup, production storage and wider platform migration remain outstanding.

### Owned files listing and management page

- Added owner-constrained paginated/searchable file listing using shared PageRequest/PageResult, one repeatable-read snapshot for count and rows, deterministic created-at/id ordering and literal case-insensitive substring search. FileRepository shares its row mapper between details and lists. Includes upload/deletion states for visibility and retry.
- Registered My files in the shared authenticated overview navigation, available without administrator grants. FilesPage reuses ResourceTable, DeleteAction and binary upload transport for file selection, 20 MiB client validation, reloadable lists, attachment downloads and confirmed versioned deletion. Upload uncertainty prompts a list refresh; duplicate clicks are guarded. Eight locales are complete. Shared deletion feedback maps file state conflicts to stale-record guidance.
- Maven verify passes 56 tests; TypeScript and 11 frontend tests pass. Rebuilt/restarted local services. Expanded HTTP regression verifies page boundaries, literal percent/underscore search, case-insensitive names, distinct pages and foreign empty lists along with existing file lifecycle checks.
- Isolated ordinary-account browser verifies chooser upload, persistence after refresh, downloaded bytes, confirmed deletion, no page errors or desktop overflow at 1213 x 886. Initial confirmation locator failed because Ant Design inserts spacing between two Chinese characters; corrected the test locator and the full interaction passed. Added browser suite to the shared CI command, remote execution unverified. Temporary fixtures removed.
- Mobile, large lists, content validation/avatars, quota/rate limits, audit, upload leases, retry/orphan cleanup and production object storage remain outstanding, as does the rest of the public-platform scope.

### Transactional file audit events and infrastructure errors

- Centralized audit insertion in AuditRepository.append; existing UserRepository.audit delegates to it, preserving callers while allowing files to reuse audit independently of user CRUD.
- File upload publication, delete request and successful metadata removal append file.upload/file.deleteRequested/file.delete within their respective state transactions. Retried DELETING operations do not repeat the request event; completion is logged only by the transaction that removes metadata. Audit page recognizes the file category and actions in all eight locales.
- Failure injection revealed unhandled database exceptions were forwarded through the protected error route and returned 403. Shared ApiExceptionHandler now handles data-access, transaction and IO failures with a generic 500 unavailable response; existing duplicate-key conflict handling remains more specific. Added response-detail privacy test.
- Maven verify passes 57 tests; TypeScript and 11 frontend tests pass. Rebuilt/restarted local services. HTTP fault injection verifies upload rollback, delete-request failure preserving downloadable bytes, final-audit failure retaining DELETING metadata, and successful retry with exactly one event per transition. File lifecycle and audit query/security regression also pass. Temporary triggers/functions, files, users and audit fixtures removed. Added fault suite to CI; remote CI unverified.
- Quotas/rate limits, leases, orphan cleanup/retries, richer audit metadata/retention, avatars, production storage and remaining public-platform migration remain outstanding.

### Per-account file quota reservations

- V18 adds reserved_bytes and enforces actual size within reservation. Upload admission locks the existing account row, reads all charged file states and reserves a slot/bytes in the same short transaction. Known content length reserves exactly its bound; unknown length reserves the full 20 MiB bound, which may conservatively reject a small unknown-length upload near capacity. Streaming cannot exceed the reservation.
- READY files charge actual size; UPLOADING and unfinished DELETING records charge actual size when available or their reservation otherwise. Capacity is only released on metadata cleanup. Empty files still consume a slot. Limits default to 1 GiB and 1000 files per account, configurable with LUMIRA_FILES_MAXIMUM_BYTES and LUMIRA_FILES_MAXIMUM_COUNT; zero disables new capacity/slots. Shared FileQuota validates boundaries without addition overflow.
- Added authenticated account files/usage response for charged bytes, file count and configured limits. File upload quota rejection has eight-language feedback. A visible usage summary is still outstanding.
- Maven verify passes 60 tests; TypeScript and 11 frontend tests pass. Rebuilt/restarted local services and applied V18. Real HTTP quota regression uses isolated metadata fixtures and two concurrent requests for the final byte: exactly one succeeds. Covers pending-deletion charge, empty-file slot cap, anonymous usage rejection and release. Existing file lifecycle and audit failure suites pass. Fixtures removed; quota suite added to CI, remote run unverified.
- Request rate limiting, stale-upload leases, retry/orphan cleanup, usage UI, content validation/avatars and production storage remain outstanding alongside the rest of the public-platform migration. Admission concurrency verification is not a throughput/load claim.

### File usage display and refresh recovery

- Added shared CapacityMeter with labeled amounts, bounded progress and limit-reached styling, including zero/reduced limits. FileUsage consumes the authenticated usage endpoint, formats byte units/counts for the current locale and explains that reservations and pending cleanup count toward usage in eight languages.
- Usage queries share the files query prefix, so upload/delete invalidation and manual list refresh update both list and capacity. Failed usage refresh preserves previous values alongside QueryFailure retry; initial failure does not invent zero usage.
- TypeScript and 11 frontend tests pass. Expanded isolated browser suite verifies usage rises from 0 B to 21 B after upload and returns to 0 after deletion, plus retained values during injected 503 and successful retry. Full upload/reload/download/delete flow still passes with no page errors or desktop overflow. Retry locator accounts for Ant Design's spacing between two Chinese characters. Fixtures removed.
- Large/zero/reduced-limit visual scenarios and full mobile regression remain outstanding. Rate limiting, upload leases, cleanup/retries, avatars, production storage and the wider public-platform scope remain active.

### Scheduled retry of terminal file deletions

- FileCleanup uses the existing scheduler every minute, selecting at most 100 local DELETING records older than one minute. Physical deletion precedes transactional metadata removal and file.cleanup audit under system:file-cleanup. Competing manual/worker completion only audits the successful metadata removal.
- Per-file failures preserve metadata/quota and defer its updated-at timestamp, so persistent failures do not indefinitely occupy the oldest batch. Failures log opaque file ID and exception type; the remainder of the batch continues. READY and UPLOADING records are excluded. No stale-upload recovery claim is made.
- Added eight-language cleanup action labeling to the audit page. Maven verify passes 62 tests, including failed-file isolation and duplicate-completion audit suppression; TypeScript and 11 frontend tests pass.
- Rebuilt/restarted local services. A real scheduled-run smoke creates an actual uploaded file plus controlled directory obstruction: verifies bytes and metadata removal, one cleanup audit, failed deletion retained/deferred, and old READY/UPLOADING fixtures untouched. Temporary filesystem/database fixtures removed. Existing audit failure/retry regression passes. Added scheduler smoke to CI; remote CI unverified.
- This worker assumes the current single local storage deployment. Shared production object storage, stale-upload leases and temporary-file recovery, ownerless READY cleanup, rate limits, avatars and the broader platform migration remain outstanding.

### Staged uploads and publication fencing

- BlobStore now exposes a single-owner Staged handle with metadata, publication and close; existing put convenience calls delegate through it. Local staging uses a deterministic UUID.upload with CREATE_NEW, bounded streaming and SHA-256. Temporary bytes are not readable through open; publication remains atomic and non-overwriting. Close discards staging while retaining published bytes, and repeated close cannot remove a later stage.
- FileService streams outside database transactions, then locks the reserved UPLOADING/version-0 metadata row before publishing and completing/auditing within one short transaction. A changed or removed reservation cannot publish. Cleanup can remove both UUID.blob and UUID.upload once the metadata state fences the publisher. Unknown commit outcomes still preserve READY bytes.
- Maven verify passes 67 tests, including invisible staging, duplicate staging/publication, safe close and lost-state publication rejection. Rebuilt/restarted local services.
- Real direct-backend HTTP fence regression pauses a two-byte request after its reservation, changes metadata to DELETING, then sends the final byte. It receives 409 with neither published nor temporary bytes and no upload-success audit. Existing file lifecycle and audit failure regressions pass. Fixtures removed and fence suite added to CI; remote run unverified.
- Lease deadlines/renewal and automatic UPLOADING expiry remain to be implemented. This establishes the publication barrier required for that recovery, not a completed timeout recovery feature. Legacy randomly named staging debris, production storage, ownerless READY cleanup and remaining platform scope are still outstanding.

### Absolute upload deadlines and scheduled expiry

- V19 adds a 15-minute absolute upload deadline, backfilled from creation time, plus an expiry index. It is a total upload duration limit, not a renewable idle timer. Publication locks metadata and then checks database wall time, including lock wait, before publishing. Expired requests cannot publish even before the scheduler runs.
- Each cleanup cycle transactionally fences up to 100 expired local uploads using SKIP LOCKED, changes them to DELETING and appends file.uploadExpired. Existing delayed cleanup then removes staged/published paths and metadata, releasing reservations. Healthy storage normally needs the expiry cycle and the following cleanup cycle; backlog/failures can extend this. Audit action has eight-language labels.
- Maven verify passes 67 tests; TypeScript and 11 frontend tests pass. Local V19 applied after rebuild/restart. Direct HTTP delayed-upload test with an expired deadline returns 409 without published/temp bytes or success audit. File lifecycle and audit failure suites pass.
- Real scheduled regression waits for expiry and cleanup: verifies abandoned temporary bytes and reservations removed, one expiry and cleanup event, valid UPLOADING/READY untouched and obstructed deletion retained/deferred. Fixtures removed. CI includes the expired-request variant; remote execution unverified.
- This does not forcibly interrupt a servlet body read already blocked on a client; an open local file descriptor may persist until that request unwinds even after its pathname is removed. Transport-level upload timeout/abort remains to be completed. Legacy unnamed staging debris, ownerless READY files, production object storage, rate limiting and broader platform migration also remain outstanding.

### Bounded upload body reads and absolute stream duration

- Configured the current Tomcat HTTP connector's separate upload read timeout (disableUploadTimeout=false and connectionUploadTimeout), default 30 seconds. LUMIRA_UPLOAD_IDLE_TIMEOUT_MS accepts 1–120 seconds. This connector setting applies to request body reads generally, including file uploads. Verified the API against installed Boot 4.1.1/Tomcat classes and [Tomcat's connector documentation](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html).
- Shared DeadlineInputStream checks monotonic elapsed time before and after reads/skips, with a 15-minute file upload bound starting before reservation. Progress does not renew it; the transport idle timeout bounds a currently blocked body read. Database expiry/publication fencing remains authoritative for admission to READY. This closes the previous unbounded inactive-body-read gap for the tested direct HTTP connector; reverse-proxy and HTTP/2 behavior is not established here.
- Socket read timeouts produce a generic 408 error, while database socket failures remain generic 500. Actual testing found container-generated timeout error dispatch was still being changed to 403 by authorization. Permit only internal ERROR dispatch so the status survives; direct requests to /error remain denied. File UI recognizes 408 with the existing eight-language upload-timeout label.
- Maven verify passes 73 tests; TypeScript and 11 frontend tests pass. Rebuilt/restarted local services. Real direct-backend test sends one byte of a two-byte upload and stops: after 30.0 seconds it receives HTTP 408, with temporary bytes, metadata and reservation removed and no successful-upload audit. Direct /error access is still 403. Ordinary file lifecycle and expired-publication regression pass. Fixtures removed; idle test added to CI, remote CI unverified.
- The 15-minute monotonic guard is tested with a controlled clock rather than a full 15-minute live request. Proxy timeouts, rate limits, production storage, legacy staging debris, ownerless READY cleanup, avatars and remaining platform migration still require work.

### Shared fixed-window login and upload limits

- Extracted PostgreSQL atomic minute buckets into FixedWindowBuckets, shared by login and file upload policies. V20 renames the existing table/index rather than discarding live counters; original login key namespaces remain unchanged. Cleanup of expired counters is centralized. Namespace validation keeps hashed identifiers within the persisted key width.
- Upload admission now uses separate upload-account/upload-source namespaces, authenticated actor identity and the existing trusted-proxy-aware ClientAddressResolver. Defaults are 30 account attempts and 120 source attempts per minute, configurable with LUMIRA_UPLOAD_ACCOUNT_PER_MINUTE/LUMIRA_UPLOAD_SOURCE_PER_MINUTE (1–10000). Source admission precedes account admission. Denials return normally from the transaction so counters commit; controller then emits 429 with Retry-After: 60 before reading the file or reserving storage. Failed valid-endpoint attempts consume allowance.
- Shared rate exception/handler returns a caller-supplied error code. Upload UI has eight-language rate-limit guidance. These are fixed minute windows and may allow boundary bursts; this is not a smooth token-bucket throughput guarantee or infrastructure-wide flood defense.
- Maven verify passes 76 tests; TypeScript and 11 frontend tests pass. Rebuilt/restarted local services and applied V20. Real HTTP concurrency test seeds the penultimate account slot: exactly one request succeeds and one is denied, subsequent denial stays capped/committed, no rejected metadata/audit is created, and elapsed-window reset works. Login limiter regression and ordinary file lifecycle pass. Fixtures removed; new rate suite added to CI, remote execution unverified. Final exception-constructor generalization compiles successfully.
- Source-wide multi-client integration, edge/proxy limits, production storage, legacy temporary files, ownerless READY cleanup, avatars and remaining platform migration are still outstanding.

### Retention and cleanup of ownerless private files

- V21 tracks orphaned_at when ownership is lost, including the user FK's SET NULL action. Existing ownerless files start their retention clock at migration time, rather than inheriting an old file creation date. An owner/orphan-marker constraint enforces consistency; reassignment clears the marker. This is an internal data invariant, not a newly exposed restoration UI.
- The scheduler fences up to 100 local ownerless READY files after seven days, records file.orphanExpired transactionally, and then reuses the existing deletion/retry worker. Eight-language audit labels distinguish retention expiry from physical cleanup. The rule applies to the current private, owner-scoped file model; future shared attachment references need their own retention guards.
- Maven verify passes 76 tests; TypeScript and 11 frontend tests pass. Rollback-only migration regression verifies existing orphan retention, owner deletion starting a fresh period for old files, reassignment and consistency constraints. Rebuilt/restarted local services and applied V21.
- Real scheduled test uploads two files, deletes their fixture account, and ages only one orphan beyond retention. That file's bytes/metadata are removed with expiry and cleanup audit, while the recent orphan remains READY with unchanged bytes. Ordinary file lifecycle and expired upload fence regressions pass. Fixtures removed; migration and scheduled suites added to CI, remote run unverified.
- Read-only inventory of the current .runtime/files shard directories found zero legacy .upload-*.tmp regular files. Automated recovery of such older unnamed debris elsewhere is not claimed.
- Avatar/profile media integration, production storage, shared attachment references, edge limits and the wider public-platform migration remain outstanding.

### Stable accessible names for asynchronous actions

- Reproduced the intermittent dictionary import timeout in both Excel and TXT flows. Failure DOM/ARIA evidence shows a zero-width, transparent loading icon retained during Ant Design's leave motion, making the accessible name `loading 预览并校验`. The visible button and dictionary parser were still present; this was not an Excel parser failure.
- Added shared AsyncButton with explicit localized label, aria-busy, loading and pending disablement. Undefined disabled is preserved outside pending state so inherited Form/ConfigProvider read-only behavior continues. Adopted in login, upload, export, preview, drawer/settings saves, table refresh, retry and paginated selection. Reload/delete confirmation buttons receive stable names and busy state through their native button props.
- Dictionary browser regression now holds a preview response to verify stable name, busy/disabled state and recovery before continuing TXT/XLS/XLSX preview, save, export and reload. Failures capture local screenshot, HTML and accessibility snapshots. Shared observeResponse attaches an early rejection observer while callers still await the original response; this prevents an unhandled timeout from terminating Node before fixture cleanup. Used in dictionary and branding scripts. A leftover branding fixture from the prior failed run was identified by its exact version/name/revision reason and removed under the settings lock.
- Final TypeScript, 11 frontend tests and production build pass. The complete ten-script browser command passes: dictionary, branding, agreements, watermark, maintenance, public configuration recovery, settings read-only permissions, profile fields, profile values and files. These are the existing scripts' coverage, not full navigation animation/mobile, production or capacity acceptance. No backend source or migration changed in this fix.
- The broader public-platform objective remains active: avatars, verified contact/recovery, dynamic platform capabilities, messaging/tasks and production validation are still outstanding; business migration remains excluded.

### Reusable raster normalization foundation

- Added ImageNormalizer for forthcoming account media integration. It accepts content recognized as PNG/JPEG rather than trusting a filename/MIME, reads at most 5 MiB plus one byte, checks edges up to 8192 and at most 16 million pixels before decoding, subsamples the decode and fits output within 512 pixels without upscaling. Fresh ARGB pixels are encoded to PNG with no source metadata or trailing payload. ImageIO uses a per-call memory cache; caller stream ownership and transport read exceptions are preserved.
- Normalized results protect their byte array from mutation. Tests cover JPEG conversion/aspect ratio, PNG alpha and small-image dimensions, invalid/unsupported inputs, bounded reads, stream ownership/socket timeout propagation, oversized header rejection, ancillary text/trailing-payload removal and defensive copies.
- Maven verify passes 81 tests, zero failures/errors/skips. This class is not yet wired to a request endpoint: no avatar upload, account binding, replacement/deletion lifecycle or UI completion is claimed. EXIF orientation handling, actual image input policy and account/file reference integration remain to be completed before exposing avatars. No runtime restart or migration was required for this unused foundation.

### Image orientation handling

- Added a bounded IFD0 EXIF orientation reader for JPEG APP1 and PNG eXIf. Supports both TIFF byte orders, validates offsets/counts/types, stops before JPEG entropy data and defaults to upright for missing or malformed metadata. It follows no external offsets or linked IFD chains.
- ImageNormalizer now applies the eight orientation/mirroring transforms after resizing, so extra pixel allocation remains within the 512-pixel output bound. Output dimensions reflect rotation; re-encoding removes the orientation tag to prevent double rotation on subsequent processing.
- Added pixel-exact tests for all eight transforms, both byte orders, malformed offsets/counts, truncated/random inputs, real JPEG/PNG normalization and repeated normalization without double rotation. Avatar endpoint, account binding, reference lifecycle and UI remain outstanding.
- Maven verify completed successfully: 85 tests, zero failures/errors/skips. No runtime deployment or avatar user-flow verification is claimed by these image-level tests.

### Avatar API, account binding and reference lifecycle

- V22 adds a server-controlled media type to file metadata and a versioned account-media record. A composite foreign key requires any bound avatar to be an owned READY normalized PNG; raw uploads cannot be bound through the API. Database constraints also prohibit changing the referenced file's state/owner/type or deleting it. Account removal cascades media bindings and preserves the existing file orphan-retention behavior.
- Added authenticated GET/POST/DELETE account/avatar and versioned private PNG content endpoints. Uploads share file rate limits, CSRF, quota, bounded transport, staging/publication, cleanup and audit; image decoding/normalization happens before publication. Avatar binding and old-file retirement participate in the publication transaction, including audit failure rollback. Upload finalization and avatar removal use account-first locking. Optimistic avatar versions survive removal to avoid resetting stale clients to version zero.
- Replacement/removal marks the old file DELETING transactionally; the existing worker removes physical bytes and releases quota later. Active-avatar deletion via ordinary file management returns 409. New avatars temporarily need quota for both images until old-file cleanup completes. There is no shared/public avatar endpoint or arbitrary file-ID binding endpoint.
- Maven verify passes 85 tests. V22 applied locally after rebuild/restart. Rollback-only migration regression proves normalized-type/owner/READY/deletion invariants and account-delete cascades. Real HTTP avatar regression passes, including image content/headers, metadata stripping, anonymous/foreign/CSRF rejection, stale and concurrent replacement (200/409), active-file protection, removal, persistent versions and injected audit failure preserving the old avatar without a new file. Existing file lifecycle and audit failure tests pass; final lock-order refinement was rebuilt/restarted and avatar/file HTTP regressions rerun successfully. New scripts added to CI; remote execution unverified.
- Frontend avatar editing, shared header/sidebar rendering, localized avatar feedback/audit actions and profile-schema integration remain outstanding. The full public-platform objective remains active and excludes business migration.

### Avatar editor and shared shell rendering

- Added AccountAvatar/useAccountAvatar with a shared account-avatar query and private versioned image URL for the header, dual-navigation account rail and editor. Missing images retain the default user icon. Account query data refreshes on window focus and mutations invalidate it along with file usage/list data.
- Personal profile now includes an independently saved avatar section with PNG/JPEG file selection, a 5 MiB client bound, server validation feedback, upload/replace and confirmed removal. Shared AsyncButton, DeleteAction and QueryFailure are reused; DeleteAction now accepts an external disabled condition. Local busy guards prevent overlapping actions. Changes explicitly save immediately and do not submit unsaved basic profile fields.
- Added all avatar labels, validation feedback and audit action labels in eight languages. TypeScript, 11 frontend tests and production build pass. Isolated browser regression passes invalid-file feedback, actual decoded editor/header image, refresh persistence, dual-rail rendering, removal/fallback, independent save preserving unsaved profile edits, no pageerror and no desktop overflow at 1213x886. Fixture files/accounts removed; new script joins the standard browser command. The initial navigation test used an unsupported End-key selection assumption; it now selects the actual visible option before asserting dual mode.
- Configurable avatar field visibility/required/completion, image fetch retry presentation, full mobile and cross-tab/browser coverage, contact verification and recovery remain outstanding. This is not full public-platform completion.
- Existing profile-values and file browser regressions also pass with the new editor/shared deletion props. No backend changes were made in this frontend step.

### Optional avatar profile rules and completion

- Profile schema now accepts the optional built-in avatarUrl/IMAGE field; custom IMAGE fields and changes to the built-in type/key are rejected. Existing schemas/defaults without this field retain the original editable avatar behavior and completion denominator. Administrators can add the built-in field through the existing schema form and configure visibility, required status, labels, grouping and weight, within the existing 100-field limit. No existing setting/history payloads were rewritten.
- Avatar uploads check current visibility both before decoding and under the shared settings lock before final publication. Removal checks visibility/required under the same lock. Publication lock order is settings -> account -> file; ordinary file uploads retain their existing path. Hidden avatar fields cannot be edited, and required avatars cannot be removed. Existing private account identity images still render in the shell; field visibility controls the profile editor, not disclosure to other users.
- Completion reads the actual avatar binding within its repeatable-read snapshot and awards the configured weight only when present. Hidden fields contribute no weight. Basic text profile endpoints exclude the image field; independent text saves do not submit avatar values. The avatar editor consumes the shared schema, disables removal when required, hides when configured invisible, and invalidates completion after changes. Required guidance is localized in eight languages.
- Maven verify (85 tests), TypeScript, 11 frontend tests and production build pass. Rebuilt/restarted local services. New HTTP rules suite passes weighted completion, required/hidden write denial, invalid custom image rejection, text-profile isolation and restoring a legacy schema. Existing avatar lifecycle and completion HTTP suites pass. Added HTTP suite to CI; remote execution unverified.
- Avatar browser checks pass with injected required/hidden schema responses to isolate presentation; actual enforcement is covered by the HTTP suite. Initial browser run timed out during home navigation while local build was also running; after build completion the same navigation and avatar flow passed. Full mobile, cross-tab races, schema change during upload and broader platform work remain outstanding.
- Administrator schema browser regression also passes the new add-avatar control with protected key/type and configurable visible/required switches, followed by the existing custom field save/reload/removal/history rollback flow. Fixture data removed.

### Shared encrypted-secret foundation for messaging configuration

- Inspected legacy SmtpMailService and AlertingSecretCrypto. Retained the standard AES-GCM approach rather than copying old configuration caches/Jackson 2 dependencies. Added framework-independent SecretCipher with random 12-byte nonces, 128-bit authentication tags and version/key-ID envelopes. Deployment-supplied keys must be 32 bytes; no password hashing into a key, plaintext fallback or ephemeral startup key generation is implemented.
- Authenticated data binds both envelope header and a canonical server-supplied context. Callers must include the configuration record identifier and secret field in that context. A bounded key ring accepts retained old keys for reads while all writes use the active key. Plaintext/envelope sizes are bounded; errors omit payload/key values and cryptographic exception causes. Callers own decrypted byte arrays and should clear them after use.
- Maven verify passes 91 tests, zero failures/errors/skips. Six new tests cover empty/binary/maximum-size roundtrips, randomized envelopes, context/header/key mismatch, nonce/ciphertext/tag tampering, rotation, invalid envelopes/configuration and defensive key copies.
- This is not yet wired to SMTP configuration, runtime key provisioning, a secret repository or recovery flows. No existing credentials were migrated and no mail was sent. Those integration steps remain active alongside the broader public-platform scope.

### Runtime key ring and encrypted secret persistence

- Added SecretKeys deployment binding for canonical Base64 32-byte keys and an active key ID. Missing configuration leaves unrelated platform features available while secret encryption/read operations fail explicitly; malformed nonempty configuration fails startup without echoing key data. Temporary decoded key buffers are cleared after SecretCipher copies them. No local/production key was generated.
- V23 creates platform_secret, separate from public settings/history. Internal SecretStore exposes only configured/version metadata to callers, authenticated decryption for short-lived internal use, and atomic versioned writes. Clearing stores a null tombstone and increments version; retained rows prevent stale initial writers from recreating cleared credentials. Typed caller services remain responsible for authorization and same-transaction audit.
- Added real PostgreSQL integration tests using separate random schemas for encrypted storage/context binding, concurrent initial writes (one success/one 409), enclosing-transaction rollback, missing-key behavior and clear/version lifecycle. The first run caught PostgreSQL's regex repetition-count bound; fixed the constraint before applying V23. Final opt-in Maven verify passes 98 tests with no failures/errors/skips; zero test schemas remain. Default runs skip these four integration tests unless the documented test DB environment variables are set. CI now supplies them; remote CI remains unverified.
- Rebuilt/restarted local services with V23 and no configured key ring. See secret-storage.md for provisioning, backup, rotation and internal-consumer rules. SMTP configuration, credentials masking in its DTO, transport and recovery flows remain outstanding; no mail has been sent.

### Typed SMTP configuration and credential lifecycle

- Added typed SMTP read/save/history/rollback APIs using the shared settings versions, global administration guard, bounded body and audit. Only TLS/STARTTLS configurations are accepted. Non-secret settings and credentials are separate; credentials require explicit KEEP/REPLACE/CLEAR, are write-only in JSON, and are redacted in diagnostic string rendering.
- Changing credential destination requires explicit replacement/clear. Enabling authenticated SMTP requires a configured usable key and password. Settings, secret updates and audit share one transaction. History never stores passwords; rollback requires an explicit credential decision.
- Added an explicit local key initializer with an exclusive lock, atomic 0600 configuration write, existing-key preservation and refusal to replace missing keys when encrypted data already exists. Provisioned the local key without printing it, rebuilt artifact from the preceding verify run and restarted services to load SMTP support.
- Latest Maven reports contain 101 passing tests, including three SMTP validation/serialization tests; no failures/errors/skips. Real HTTP SMTP suite now passes permissions/CSRF, secret-free responses/history, encrypted database storage, KEEP/REPLACE/CLEAR, destination protection, stale and concurrent saves (200/409), injected late audit failure rolling back settings/history/secret, and explicit rollback credentials. Fixture settings/secrets/accounts are removed. CI initializes a local key after migration, restarts and runs this suite; remote CI remains unverified.
- SMTP frontend configuration, transport, delivery/retry and recovery remain outstanding. No mail was sent. Broader public-platform migration remains active, excluding business migration.

### SMTP configuration page using shared settings components

- Added `/dashboard/smtp` to the shared permission-aware route/navigation registry and workspace. The page uses SettingsSection and SettingsHistory, extended with optional extra form fields and defaults so credentials can be supplied separately during save and restore. Existing ordinary setting pages use the same paths without extras. Successful saves/reloads/restores reset form-only inputs and leave response metadata in the query cache; passwords are never populated from reads or history.
- Added explicit KEEP/REPLACE/CLEAR controls, configured/key availability status, a bounded password input cleared when changing action and after success, and destination-change feedback. History shows only ordinary SMTP fields and provides a separate credential decision for restore. Read-only forms and restore actions remain disabled. The page clearly states that mail delivery is not yet connected.
- All page/status/error messages are translated into eight languages. SMTP secret audit actions use localized labels. New route follows both navigation layouts and the global settings-write grant. Shared section/history extensions do not introduce SMTP credential fields into unrelated sections.
- Type checking, 11 frontend tests and production build pass. SMTP real-browser regression passes replacement/reset, reload, changed-destination rejection preserving edits, clear, history restore with explicit replacement, read-only save/restore disabling and no pageerror or desktop overflow. Initial test locators were corrected to the actual reason-field name, and the password-action select received an explicit accessible name. Brand settings browser regression also passes save/reload/conflict/history restore after the shared component change. Test fixtures removed; SMTP browser suite joins the standard browser command. Remote CI is not verified.
- Mail transport, retries, verification/recovery and the rest of the public-platform goal remain active. No email was sent and business migration remains excluded.

### Internal SMTP transport and consistent credential loading

- Added Boot-managed mail dependency, bounded single-recipient plain-text OutgoingMail and internal SmtpConnectionSource. Configuration and password are read in a repeatable-read snapshot; disabled or unauthenticated settings do not decrypt unused credentials. Owned password bytes are cleared after the attempt. Jakarta Mail requires an authentication String; no sender/session cache is introduced and immediate erasure of library strings is not claimed.
- Added a one-attempt SmtpGateway with required STARTTLS or implicit TLS, hostname verification, finite connect/read/write timeouts, no partial delivery and no debug output. Safe enum results distinguish pre-send connection/authentication failures from uncertain delivery; server acceptance is not inbox delivery. A QUIT failure does not overwrite confirmed DATA acceptance. No raw server response is returned by the transport.
- Final Maven verify passes 110 tests with no failures/errors/skips, including nine new mail tests. Tests cover UTF-8 MIME composition, header/list/size validation, credential clearing, disabled/no-auth reads, safe decryption failures, success versus unknown outcomes and an actual loopback Angus handshake refusing a server without STARTTLS before sending credentials or mail. Successful TLS delivery against a real SMTP fixture is not yet verified. Rebuilt/restarted local services successfully.
- No user-facing sending endpoint, persistent delivery task, retry policy or account recovery consumer is wired yet. Per-socket timeouts do not establish a whole-job deadline. These remain part of the active public-platform goal; no external email was sent.

### Real loopback TLS delivery verification

- Added disposable keytool-generated localhost certificate and loopback SMTP fixture, without committing private keys or changing production trust settings. The real Angus provider now passes authenticated implicit TLS and STARTTLS submission, UTF-8 subject/body and SMTP dot handling.
- Negative tests prove default trust rejects the self-signed certificate, a locally trusted certificate still requires matching hostname, authentication refusal never submits DATA and a disconnect after DATA returns DELIVERY_UNCERTAIN rather than acceptance or a safe retry claim. Test-only trust is injected through the package-private factory; production retains default trust and hostname verification.
- First test run exposed an overly narrow fixture assumption about SASL PLAIN authorization identity. The fixture now accepts either empty or matching sender authorization identity while retaining exact authentication identity/password validation. All five new tests then passed, followed by full Maven verify: 115 tests, no failures/errors/skips. Only tests/documentation changed in this step; runtime restart is unnecessary. No external mail was sent.
- Persistent delivery tasks, retry handling, user-facing delivery status and verification/recovery remain outstanding within the full public-platform goal.

### Durable encrypted mail queue foundation

- V24 adds encrypted mail deliveries and explicit PENDING/LEASED/SENDING/ACCEPTED/FAILED/UNCERTAIN/EXPIRED states. Stable operation UUIDs make enqueue first-write-wins without replacing content or extending deadlines. Payload encryption binds the task ID, with a 64 KiB serialized bound and maximum one-day lifetime; caller transactions can include enqueue and audit atomically.
- Added internal MailDeliveryStore with skip-locked atomic claims, new lease tokens, protected content reads and transitions, and bounded 1000-row expiry recovery. Expired pre-send claims can be recovered; expired sending leases become UNCERTAIN and never automatically retry. Only pre-submission connection failures schedule retries, with exponential waits and five total claims maximum. Authentication/configuration failures remain terminal. Old tokens cannot start or overwrite newer attempts.
- Real PostgreSQL tests cover encrypted idempotent storage, context-copy rejection, outer transaction rollback, concurrent claim exclusion, old-token fencing, expiry uncertainty, delayed capped retries, payload/lifetime bounds and terminal transitions. An initial generic assertion overload compilation issue was fixed. Incremental compilation later retained a stale six-test class; final clean verify explicitly ran all seven queue tests and 122 total tests, with no failures/errors/skips.
- V24 applied locally; queue is empty. Services are updated to the clean-built artifact. No worker or sending API is connected yet, and no external mail was sent. See mail-delivery.md for state/consumer requirements. Worker scheduling, end-to-end deadline, status UI/permissions, audit, retention and recovery remain active work.

### Bounded background mail consumer

- Added MailDeliveryWorker polling every two seconds after a ten-second startup delay, with four virtual-thread slots and at most four claims per poll. It reads configuration/content before the send transition, commits state plus started audit before network IO, and commits the result plus audit afterward. Expired claims do not send; configuration failures fail before network; completion failures leave SENDING for conservative recovery.
- Network attempts have a 60-second interruption deadline, below the two-minute lease. Deadline outcomes are UNCERTAIN even when the transport reports a connection failure caused by interruption. Deadline cancellation is synchronized to avoid a late interrupt affecting state persistence. Application shutdown stops claiming and interrupts active tasks. Full database/config-load absolute deadlines remain outside this boundary.
- Added eight worker tests covering ordered transaction/send calls, expired leases, missing configuration, unexpected transport errors, deadline classification and clearing, failed completion, four-slot admission, and actual virtual-thread socket closure against a stalled loopback SMTP peer. The first concurrency test ended before fixture tasks returned; fixed the fixture to wait for completion and handle interruption explicitly. Final clean Maven verify passes 130 tests, no failures/errors/skips.
- Added localized mail audit action labels in eight languages; frontend type checking and 11 tests pass. Updated local services after confirming an empty queue; no external mail sent. Successful queue-to-SMTP integration with actual persistence, public delivery status/permissions, expiry audit/retention and account verification/recovery producers still require implementation/verification. Full public-platform migration remains active.

### Permission-protected mail delivery query and page

- Added GET /api/platform/mail-deliveries, requiring global platform:audit:read. Bounded pagination, literal ID substring search and exact state filters share a repeatable-read snapshot with the total. The explicit projection contains only IDs, states, counts, result codes and timestamps; it never selects payloads or lease tokens. No send/retry mutation was introduced.
- Added /dashboard/mail-deliveries using the shared ResourceTable, shared route/navigation registry, state filter, task-ID search, manual refresh and ten-second polling. A 403 hides cached rows; temporary errors retain rows with feedback. Added eight-language state/column/help labels and corrected mail audit category grouping.
- Clean Maven verify passes 130 tests; frontend type checking, 11 tests and production build pass. Real HTTP regression covers 105-row pagination/nonoverlap, status filtering, exact safe fields, invalid parameters, anonymous/ungranted/SELF denial, ALL access and immediate scope revocation. Browser regression covers state rows, filter/search, failed refresh and recovery, no pageerror and no desktop overflow. The initial search locator used a mismatched control role; switched to the actual placeholder and the complete flow passed.
- Fixtures use only terminal states and are removed; queue confirmed empty. New HTTP/browser checks join CI/standard browser scripts, remote CI unverified. Local services updated. Manual uncertain-result handling, expiry audit, retention, account verification/recovery and the full public-platform goal remain active. No external mail was sent.

### Recovery auditing and persistent TLS delivery integration

- Expiry recovery now returns changed IDs/states, and the worker commits recovery with its audit records in one transaction. Audit failure rolls back the batch; repeated recovery emits no duplicate events. Added localized mappings for requeue, expired, exhausted and uncertain recovery outcomes.
- Added real PostgreSQL + encrypted queue + worker + real loopback TLS SMTP + audit integration. The configuration source is a test fixture; queue/state/audit persistence and SMTP transport are real. Success records acceptance and two audit events. Injecting acceptance-audit failure leaves SENDING despite the SMTP server receiving DATA; later expiry produces UNCERTAIN with exactly one recovery audit and no automatic resend. A separate database regression proves expiry-audit failure rolls back the state transition.
- Final clean verify passes 132 tests, no failures/errors/skips; frontend type checking passes. Temporary schemas removed, main queue empty, local services updated. No external mail sent. Manual resolution/retention and account verification/recovery producers remain outstanding alongside the full public-platform goal.

### Account email verification storage

- Inspected the current profile contract: it has no editable verified email. Added V25 with separate verified/pending addresses, versioned challenge replacement, verification time, request counters and failed-attempt accounting. Verified addresses are case-insensitively unique; deleting an account removes its email row.
- Added reusable 256-bit VerificationToken generation/canonical digest validation with redacted diagnostic rendering. Only SHA-256 digests persist. AccountEmailStore serializes account challenges, uses the configured lifetime, cooldown, daily request limit and failed-confirmation limit (defaults: 600 seconds, 60 seconds, 10 requests and 5 failures). Confirmation is single-use and account-bound; failed attempts return false so their counters commit. The caller must still provide authorization, CSRF and atomic mail enqueue/audit.
- Final clean verify passes 139 tests, no failures/errors/skips. Seven new tests cover token properties and real PostgreSQL verification lifecycle, uniqueness, concurrency, limits and caller rollback. This is an internal foundation, not a completed email verification UI/API. See account-email.md for the next service-layer requirements. No email sent; full public-platform migration remains active.

### Responsive account controls and shared menu

- Replaced the overflowing mobile inline account buttons with an avatar menu. New AccountMenu shares identity, profile, password, sessions and logout actions between the mobile header and desktop dual rail; the rail retains its controlled open state for auto-hide behavior. Workspace centralizes logout and drawer callbacks. Desktop tree controls remain available, while desktop dual mode keeps account actions in the rail.
- Header controls wrap within the viewport, long identity labels wrap inside a bounded popup, and mobile branding can wrap. Corrected mobile navigation trigger specificity so Ant Design button styling does not displace the fixed bottom trigger.
- Type checking and 11 frontend tests pass. New real browser suite passes both layouts in eight languages at 320px, account drawers and navigation open/close at 390px, desktop account placement, and no pageerrors or horizontal overflow. Screenshots reviewed. Added to the existing browser/CI command; remote CI not run. Initial immediate Escape closure was unsuccessful; explicit close buttons pass, with keyboard focus/Escape still requiring investigation. Full platform adaptation and email verification integration remain active.

### Account email service and live API verification

- Current account endpoints now compose password reconfirmation, configured SMTP credentials, challenge storage, encrypted queue and audit in one transaction. Confirmation maps invalid results after service commit so failed-attempt counters persist. Eight-language plain text templates follow the configured code lifetime; responses never include it. A separate committed per-account minute limiter covers requests and confirmations.
- Found and fixed a runtime integration defect: the email-verification bucket namespace exceeded FixedWindowBuckets' 15-character bound. Replaced it with email-verify and added a regression for account-specific key use and denial propagation.
- Clean verify passes 144 tests with zero failures/errors/skips, including service-level real PostgreSQL queue/challenge/audit rollback tests. Verified prior runtime PIDs, restarted with the new artifact, and passed real HTTP status, authorization, CSRF, wrong password, account-bound token, committed failure, single-use, unique-email conflict, attempt cap and rate-limit checks. Isolated fixtures removed. HTTP suite joins CI; remote execution unverified.
- No external email sent. Positive HTTP confirmation uses a database challenge fixture; actual user-facing request/receive/confirm flow still needs frontend implementation and end-to-end verification. Full public-platform goal remains active.

### Account email UI

- Added an independently saved email drawer within personal profile, using shared AsyncButton, QueryFailure, API transports and eight-language messages. It presents verified/pending addresses, expiry, password reconfirmation, queue acknowledgement and code confirmation without submitting unsaved basic profile values. Password clears after an attempt; credentials/code reset on closure.
- Browser regression found and fixed two issues: close controls now reflect pending state, and email queries use zero stale time so reopening loads external challenge changes. Real browser checks pass request-error feedback/password clearing, preserved unsaved profile data, real backend confirmation, refresh persistence and mobile viewport bounds. The request producer is intercepted and the challenge is a database fixture; no external mail sent, and this does not establish inbox delivery end to end.
- TypeScript and 11 frontend tests pass. Browser check joins the existing standard CI command; remote CI unverified. The broader public-platform goal, full keyboard/accessibility QA and actual request-to-inbox verification remain active.

### Account keyboard focus and interrupted drawer motion

- Reproduced loss of return focus when a drawer closes before its entry motion completes. The library's afterOpenChange completion path is insufficient for that interruption. Added shared PlatformDrawer using the installed Ant Design focusable configuration: capture the launcher on open, restore after the closed DOM state on an animation frame, cancel stale callbacks, and avoid stealing focus from another open drawer. Nested windows return to their parent launcher.
- FormDrawer, sessions and email use this shared wrapper. Shared form close controls now reflect pending status. AccountMenu uses native dropdown autofocus and focuses its launcher before invoking drawer actions, so a disappearing menu item is not the return target.
- Real keyboard suite passes desktop tree/dual and 390px tree/dual, Enter opening, arrow-key menu selection, Tab within the drawer, Escape closure and return to the launcher. Nested profile-to-email Escape retains the parent and restores its email button. Added to standard browser/CI entry. Initial test incorrectly equated the semantic dialog node with its focusable drawer wrapper; corrected the assertion to the actual drawer root. A test syntax typo was corrected before the final pass. This is account-flow coverage, not a claim of full application accessibility or navigation-motion completion.

### Password recovery challenge foundation

- V26 and internal PasswordResetStore reuse VerificationToken for verified-email-only, account-bound recovery. Disabled or unverified accounts cannot obtain a challenge. Ten-minute expiry, minute cooldown, daily ten-request cap and five failed confirmations are enforced in PostgreSQL; successful consumption keeps the request counters.
- Challenges bind the current password hash and email version. Password/email changes invalidate outstanding challenges. Consumption serializes on account/email/challenge locks, changes the password, deletes only that account's sessions and clears the challenge atomically. Caller audit failure rolls everything back; false results commit failed-attempt accounting.
- Clean Maven verify passes 151 tests with zero failures/errors/skips, including seven real PostgreSQL integration tests for eligibility, single-use/session isolation, expiry/replacement/attempt limits, credential changes, rate windows, rollback, concurrent consumption, foreign-token denial and account deletion. No external mail sent. See password-recovery.md for required public-service boundaries: anti-enumeration responses, anonymous throttling, password validation, SMTP enqueue/audit and frontend are still pending.

### Password recovery orchestration

- Added internal PasswordRecoveryService composing global SMTP availability, verified recipient eligibility, reset challenge, encrypted queue and audit atomically. Requests return void for unknown, disabled, unverified and cooldown accounts, with no address/token returned. Eight-language plain-text recovery templates follow the configured code lifetime.
- Shared PasswordPolicy now serves both authenticated password changes and recovery. Confirmation computes a replacement hash for unknown usernames too, but does not claim constant timing. The same-password check runs only after proof validation inside the account lock; successful reset and audit commit together, while false outcomes preserve failed-proof counters and audit.
- Clean verify passes 157 tests, zero failures/errors/skips. Six new real PostgreSQL service tests cover encrypted queue-to-confirmation, session revocation, ineligible/cooldown no-op requests, proof-before-same-password validation, failed-attempt persistence, request and confirmation audit rollback, unavailable SMTP and invalid password. No external mail sent. Anonymous throttling/public adapters and frontend remain outstanding; the internal service is not exposed directly.

### Anonymous password recovery adapters and limits

- Added exact POST request/confirm routes under /api/auth/password-recovery, retaining session CSRF protection and 2048-byte body bounds. Normal requests return empty 202; invalid confirmation maps to 400 after service commit, while successful reset returns 204. Token/new-password DTO fields are write-only and diagnostic rendering is redacted.
- Added independent committed recovery source/account minute buckets, reusing trusted ClientAddressResolver. Default limits are 60 per source and 10 per normalized username across request and confirmation. Source denial prevents creation of further account buckets. Unit regressions cover namespace validity/normalization, source-first denial and configuration validation.
- Clean verify passes 159 tests with zero failures/errors/skips. Execution was slow but continued; inspected the live Maven process and reports rather than restarting an unfinished test run. Rebuilt/restarted local services successfully. Real anonymous HTTP regression passes CSRF denial, uniform ineligible request status, committed failed proof count, same-password rejection, successful password reset, two-session revocation, token replay denial, old-password rejection/new login and 429 accounting. Independent fixtures removed and CI script added; remote CI unverified. No external email sent; request tests only use accounts without verified addresses, and confirmation uses a database challenge fixture. Login-page recovery UI and actual inbox end-to-end flow remain outstanding.

### Login-page password recovery

- Added an eight-language recovery drawer launched from login, reusing PlatformDrawer, AsyncButton and shared NewPasswordFields with the authenticated password-change form. Request validates the username only; confirmation validates code/new password/matching confirmation. Busy controls prevent overlap; failed confirmation clears password inputs; success returns to login with username retained and password cleared.
- Browser regression exposed duplicate default username field IDs between login and recovery; assigned the recovery form its own name. Also fixed shared API handling of empty successful response bodies: recovery request returns empty 202, which previously caused a JSON parsing failure. ApiError uses explicit properties so the real client can be tested under the existing Node TypeScript test runner.
- Twelve frontend tests pass, including empty 202/204, JSON success, structured API failure and malformed-JSON regression. Real 390px browser flow passes request presentation, actual invalid/valid confirmation, password clearing and new-password login, without pageerrors or overflow. Producer request is intercepted and the challenge is a database fixture; no external mail sent. Script added to standard browser/CI entry. Actual inbox delivery and broader public-platform work remain active.

### Recovery service through real loopback TLS delivery

- Added a persistent integration spanning stored SMTP JSON, encrypted credentials, actual SmtpConnectionSource, PasswordRecoveryService issuance, encrypted queue, MailDeliveryWorker, real Angus TLS delivery and BCrypt reset. The received MIME message supplies the confirmation code; tests assert recipient/subject, encrypted payload, accepted state, single-use confirmation, password replacement, account-specific session revocation and recovery audit.
- Reuses the disposable loopback SMTP/certificate fixture and actual V12/V23-V26 migrations in an isolated schema. SettingStore write-only collaborators are unused; source reads and credentials are real. Temporary certificate trust remains confined to the test gateway. No external email sent or main-database SMTP setting changed.
- Clean verify passes 160 tests with zero failures/errors/skips, including the new full service-to-TLS-to-reset case. Only tests and documentation changed, so no runtime restart needed. This complements separate HTTP/browser tests; it does not establish external inbox delivery or a single browser-to-external-inbox end-to-end proof. Broader public-platform adaptation remains active.

### Personal account security history

- Added an authenticated, account-scoped history endpoint and nested profile drawer reusing ResourceTable, PlatformDrawer and shared localized audit labels. The explicit whitelist covers password/email actions and session revocation; projections omit session targets. Same-name historical session events are excluded using the current account creation date. V27 adds matching partial indexes.
- Password changes now append audit in the password/session transaction. Actual HTTP regression passes 105-row pagination, bounds, safe fields, foreign-account and old-name isolation, plus forced audit failure rolling back password and session updates.
- Browser regression passes localized events, cached rows during refresh failure, successful refresh recovery, mobile bounds and preservation of the parent drawer. Typecheck, 12 frontend tests and production build pass; backend Surefire reports 160 tests with no failures/errors/skips. Scripts join the existing CI entry; remote execution remains unverified. Login/IP/device events and retention remain outstanding.

### Login security event integration

- LoginAudit records successful and failed authentication for resolved account IDs in the existing audit store. Unknown or oversized submitted usernames are not retained. Personal security history includes both events, with shared eight-language action labels and an expanded partial target index in V28.
- Successful authentication checks maintenance first; an audit persistence failure clears the security context and invalidates the new session before returning 503. Normal authentication failures retain the generic response. Rate-limit/CSRF rejection and the password-size prefilter do not create login audit entries; source/device metadata and retention remain outstanding.
- New real PostgreSQL test passes identity resolution, unknown-input exclusion, action separation and same-name account recreation. Frontend typecheck and 12 tests pass; backend packaging passes. Restarted runtime with V28 and passed actual HTTP success/failure event history, pagination/isolation, password/session/audit rollback and forced login-audit failure returning 503 with no authenticated session left behind.

### Login source context

- V29 adds nullable bounded source-address and user-agent columns to the shared audit store. Login success/failure handlers use the existing ClientAddressResolver (explicit immediate-proxy trust only); LoginAudit canonicalizes literal addresses and removes control/format characters from user agents, keeping at most 256 code points. Unknown account names remain unrecorded. Historical events remain readable with null metadata.
- Personal security history explicitly projects the two new fields while retaining account isolation. The existing shared table renders them as escaped wrapping text in eight languages, with a dash for missing historical values. User-agent is client-supplied context, not verified device identity. Administrator audit projection is unchanged.
- Validation: full clean verify passes 162 tests with no failures/errors/skips, including migration-backed context normalization/null compatibility checks. Frontend typecheck, 12 tests and production build pass. Restarted V29 runtime passes actual HTTP login metadata, historical null fields, account isolation, pagination and audit-failure session invalidation. Browser checks pass localized literal user-agent text, refresh failure/recovery, nested closure and mobile bounds. Screenshot inspection caught a pre-existing wide-drawer clipping defect missed by document overflow checks; shared PlatformDrawer now bounds its wrapper to its container, and the regression asserts the dialog rectangle fits the viewport. Repeated screenshot inspection confirms the title/close control are fully visible; the table scrolls within the drawer. Rejection events and retention remain outstanding.

- Shared drawer follow-up: typecheck and security-history browser verification pass after width correction. The first full account keyboard run failed closing the password drawer with Escape in mobile dual mode; a repeat with diagnostic output passed all four mode/viewport combinations without a behavior change. This intermittent issue remains unresolved, not a confirmed fix. Diagnostic capture is retained for future reproduction.

### Escape handling for interrupted account transitions

- Reproduced the previously intermittent failure in the seventh mobile-dual stress iteration: focus stayed on the session drawer close button, with an account menu already hidden, yet Escape did not close the drawer. Installed portal code retains closing layers in its global Escape stack. Shared PlatformDrawer now handles unconsumed Escape originating inside its own DOM, before that global stack; portal children and handled events retain priority. Keyboard disabling, IME composition and the installed 200ms composition-end protection are respected. Existing width/focus-return behavior is preserved.
- After the fix, eight mobile-dual stress rounds passed. Default desktop/mobile tree/dual checks also pass, including synthetic composition-state protection; security-history browser regression confirms its page-size dropdown consumes Escape without closing the drawer. Added failure diagnostics and an opt-in stress mode to the existing keyboard suite. Documented reusable behavior in frontend-components.md. This resolves the reproduced path; it does not certify every application overlay or the broader platform objective.

- Final validation for the Escape change: frontend typecheck, 12 tests and production build all pass. Backend unchanged in this step.

### Internal module state foundation

- PlatformModule now declares required modules, with identity/platform marked required. Registry validates bounded IDs and rejects required-to-optional dependencies in addition to the existing graph checks. Optional modules start disabled; core modules cannot be disabled.
- V30 and ModuleStateStore persist versioned state and revision history. A graph-wide transaction lock serializes dependency validation and changes; shared audit commits atomically. State reads omit unregistered historical records. No state mutation API or UI is exposed yet: authorization adapters, optional-module boundary definitions and runtime/navigation enforcement remain next. See module-lifecycle.md for lock order and integration requirements.
- Added real PostgreSQL regressions for dependencies in both directions, stale/no-op changes, invalid/unknown input, audit rollback, same-version concurrency and cross-module dependency races. Full clean verify passes 168 tests with zero failures/errors/skips after final default-state rules were applied. Frontend boundary and existing 12 tests pass.

- Loaded the new runtime and V30 successfully. Real HTTP smoke passes login/CSRF, module registry reads, user pagination, permission revocation and logout replay denial. No module state writes were exposed or performed on the live database.

### Module administration adapters

- Added ModuleAdminService/Controller outside the dependency-independent module package. Existing module GET now includes live state with a separate stateVersion while preserving code version. Exact PUT state route uses bounded DTO/CSRF and the new module-write permission; service rechecks grants and global scope in the same transaction before the state engine. Domain conflicts map to structured HTTP errors.
- V31 grants the existing administrator the new permission; bootstrap and role permission catalog include it. No optional runtime module or UI switch is introduced yet, so this does not claim endpoint/task/navigation gating or package lifecycle completion.
- Added adapter regressions and a real HTTP fixture suite for read/write separation, SELF versus ALL scope, CSRF, core protection, stale/invalid input, no-op audit and immediate revocation. CI entry includes the new script; remote execution unverified. Full clean verify passes 171 tests with zero failures/errors/skips.

- Loaded V31 runtime. Real module HTTP suite and existing login/module/user smoke both pass, including immediate SELF-to-ALL scope change and role revocation. Initial oversize-body expectation was corrected from 400 to the shared 413 contract after observing the real response; production handling was preserved. Fixtures removed.

### Resolved-handler module access checks

- Added ModuleEndpoint ownership metadata, method-over-class resolution, startup handler validation and an MVC interceptor after the security filter chain. Unknown owners fail startup; optional missing/disabled state fails closed with 503 on new requests, reread each request. Required endpoints skip the extra state query. The existing module administration controller declares platform ownership.
- No optional module is registered yet. Undeclared legacy scaffold handlers retain core behavior; future optional extraction must enumerate all its handlers. In-flight requests, internal calls and background jobs are explicitly outside this interceptor's scope. Navigation enforcement and UI controls remain pending.
- Added state-transition, method-override, unknown-owner and missing-state regressions. Full clean verify passed 175 tests; an additional MVC request-chain test then passed in the five-test interceptor suite, proving disabled handlers are not called and re-enabling resumes execution. No implementation changed between these runs.

- Restarted with the interceptor and startup validator. Actual module-administration HTTP checks and existing login/user/module smoke pass; the local service remains available. Optional-feature extraction and UI remain pending.

### Audit console optional module and management UI

- Extracted audit-console ownership for the read-only audit controller/page, retaining audit production and personal security history in core. V32 preserves the existing enabled feature with a state revision and audit entry. Runtime manifest now has three modules; default optional registration remains disabled outside this explicit preservation migration.
- Auth capabilities include enabledModules. Shared route/navigation checks require both permission and module capability. ModulesPage replaces the inline manifest table with ResourceTable/FormDrawer, reason/version-based writes, read-only/core restrictions, error handling and eight-language UI. BroadcastChannel synchronizes same-origin tabs; focus/30s foreground refresh and module-unavailable responses refresh remote changes, while backend denial is immediate.
- Full backend clean verify passes; frontend typecheck and 13 tests pass. Actual HTTP toggle test confirms existing-session denial, recovery and continued core logging, followed by module-permission and legacy login/user smoke passes. Real browser confirms toggle, cross-tab capability updates, direct URL/reload denial, re-enable and mobile bounds; screenshot reviewed. Original enabled state restored; audit history retained. Standard CI/browser entries include these suites, remote CI unverified. Production frontend build passes.

### Module revision history

- Added per-module revision pagination with repeatable-read count/rows, descending version ordering and registered-module validation. Exact history GET requires module-read authority plus global scope in the service. Read-only global access is independent of state-write access and remains available when audit-console is disabled.
- ModuleHistoryDrawer reuses shared drawer/table and eight-language labels; module actions expose it only to global readers. Query keys isolate module/page, opening refetches history, and module switches derive initial pagination without remounting the drawer's focus controller.
- Full backend clean verify and frontend typecheck/13 tests pass. HTTP module suite now verifies history fields/order, empty and unknown modules, bounds and SELF/ALL read isolation. Desktop history open/refresh/close and 390px drawer bounds/internal horizontal scrolling passed in the module browser suite. Mobile screenshot reviewed; production build passed. Backend reports contain 177 tests with zero failures/errors/skips.

### 公共草稿存储适配

- 从旧 user-drafts 抽取账号级通用能力，新增 V33 和独立 drafts Controller/Service；不迁业务表单。支持读取、带 UUID 修订保存与删除，账号 UUID 隔离、CSRF、单份/总量/数量配额和事务审计。
- 7 项真实数据库专项及运行 HTTP 回归通过；前端审计动作八语言、类型检查、13 项测试、生产构建通过。当前后端报告合计 184 项零失败/错误/跳过，最后存储调整后复跑草稿专项。
- 共享前端恢复/保存交互和公共表单接入仍未完成。详见 account-drafts.md，不将服务端完成视为整项完成。

### Shared draft UI verification

Organization creation now consumes the shared account-draft hook and DraftActions component. It supports manual save, confirmed restore/delete, conflict reload and revision-specific cleanup after successful creation. Field codecs exclude record versions/permissions and reject incompatible payloads. Eight-language messages are complete.

Browser coverage passed for reload persistence, cross-tab conflicts, preserved current input, stale cleanup preserving another tab's newer draft, successful cleanup of the consumed revision, and 390px drawer bounds/screenshot. Frontend typecheck, 15 tests and production build passed. The browser script is registered in test:browser. Other public form adapters and full platform migration remain open.

### Account draft management completed

Added owner-only paginated metadata listing and the account draft drawer, reusing ResourceTable, PlatformDrawer and DeleteAction. Search, pagination and revision-checked deletion are available from tree desktop, mobile and dual-navigation account entries. Draft content is not returned in the list. Stale deletion preserves data and refreshes the list instead of silently retrying.

Eight database draft tests, running HTTP pagination/field/isolation checks, and browser list/search/stale-delete/delete/entry/mobile checks passed. Typecheck, 14 frontend tests and production build passed. Other public form adapters and broader platform migration remain open.

### Feedback internal adaptation

The legacy feedback capability is now exposed through the new platform boundary. V34–V38 add storage, conflict-safe dictionary seeds, shared normalized image references, bootstrap grants and an enabled optional-module state. FeedbackController/Service enforce module state, owner isolation, independent read/create grants and global management scope before calling the internal store. The page uses ResourceTable, FormDrawer/PlatformDrawer and a shared safe rich-text editor with all eight locales; image insertion supports upload, paste and drop. V43 adds the feedback creation adapter on the account-draft protocol with a bounded schema, restore-after-cancel behavior and consumed-revision cleanup. Real HTTP and browser smokes cover policy resolution, sanitization, image authorization, deletion protection, draft save/restore/cleanup, processing/version conflicts, revocation and responsive bounds.

### Platform event Outbox foundation

- V39 adds a database-backed event boundary independent of business modules. Producers can append a normalized JSON event in their local transaction; the source/type/key tuple and event UUID are both first-write-wins idempotency fences. Payloads are stored as bounded PostgreSQL `jsonb` and are never returned by the operational API.
- `PlatformEventOutboxStore` implements SKIP LOCKED claims, two-minute lease tokens, old-token fencing, bounded exponential retry, dead-letter transition and expired-lease recovery. `PlatformEventOutboxQuery` and `/dashboard/event-outbox` expose only safe metadata under global `platform:audit:read`, with pagination, literal search, exact status filters and temporary-error row retention.
- Five real PostgreSQL tests cover canonical JSON, invalid/bounded input, idempotent keys, concurrent claims, delivery fencing, retry/dead-letter and lease recovery. HTTP and browser smokes pass permission/global-scope, immediate revocation, 105-row pagination, filtering/search and failed-refresh recovery. Frontend typecheck and production build pass; V39 is loaded in the local runtime.
- This is the reusable platform boundary only. No competition event consumer, message-bus relay, manual replay approval, retention job or production capacity/failover claim is made; those remain active before the business migration.

### In-app notification foundation

- V40/V41 add an account-scoped notification inbox with event linkage, per-recipient dedupe, bounded JSON parameters, expiry filtering and unread indexes. The feedback producer writes localized notification keys in the same transaction as feedback and audit records; administrators with feedback management scope do not receive self-operation notifications.
- Authenticated account APIs support paged inbox reads, unread counts, idempotent single-read and all-read operations. The workspace header exposes a localized bell popover with polling, safe route links, retained rows during temporary refresh failures and eight-language messages.
- PostgreSQL store tests cover recipient isolation, dedupe, pagination, expiry, parameter/link bounds and concurrent first-writer wins. HTTP and browser smokes cover unread state, localization, mark-all-read, persistence, desktop overflow and the existing feedback flow. External push channels, retention cleanup and cross-device delivery remain outside this scaffold boundary.

### Global online-user session management

- Added `/api/platform/online-users` on top of the existing Spring Session table. The query is repeatable-read, server-paged and literal-searchable by account/display name; it returns only session identifiers, account metadata and timestamps. Expired sessions are excluded and no session cookie, attribute or lease data is exposed.
- Global `platform:users:read` is required for listing and global `platform:users:write` is required for revocation. Individual revocation deletes the session before writing an audit event; the user-wide endpoint revokes all sessions for one account and records one aggregate audit event. Existing account isolation and self-session controls remain unchanged.
- `/dashboard/online-users` reuses the shared ResourceTable, DeleteAction, error handling and eight-language catalog. It supports polling, search, desktop/mobile table bounds, confirmation, 403 hiding and failed-refresh row retention. The browser smoke proves a real temporary account is listed and searchable, admin revocation invalidates that account's session, ordinary users receive 403, and retry recovers after a 503.
- The backend repository integration tests, HTTP smoke, frontend typecheck, production build, 14 Node tests and full browser suite pass. This is a platform administration capability; device/IP geolocation, forced logout messaging, external session registry and distributed invalidation remain outside the current local Spring Session boundary.

### Platform operations monitoring

- Added `GET /api/platform/monitoring/summary`, a read-only aggregate over users, active Spring Sessions, module state, event Outbox, mail delivery, notification inboxes and the last 24 hours of audit events. The response is a bounded safe projection with no payload, session attribute or secret data.
- The endpoint requires global `platform:audit:read`; the `/dashboard/monitoring` page reuses the shared resource error/retry pattern, Ant Design responsive grid and the eight-language catalog. A fifteen-second refresh keeps the view current while preserving the last successful cards during a temporary failure.
- The monitoring repository integration test, HTTP permission/counter smoke, browser failure/retry and mobile no-overflow checks pass. This is an operational read surface only; metrics export, alert routing, distributed tracing, retention policies, capacity testing and failover drills remain production follow-up work.
