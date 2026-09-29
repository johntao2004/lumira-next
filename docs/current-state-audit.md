# 当前系统功能检查

## 2026-09-16 平台脚手架闭环复验（最新）

- 公共平台脚手架已完成本地闭环：31 个服务端内置导航入口（含 Passkey）、静态路由组件注册、双层/分区运行时菜单、统一表单/抽屉/表格/异步按钮/气泡反馈组件均已对齐；业务功能迁移仍排除在本轮范围之外。
- `pnpm test`（17/17）、`pnpm typecheck`、`pnpm build`、`pnpm test:browser`、`pnpm db:schema-smoke`、`smoke.py`、Passkey 回归及项目 JDK 25 下的 `./mvnw -q clean verify` 均通过；前端 8010、后端 8090 readiness 返回 HTTP 200。
- 浏览器回归已经覆盖 TXT、XLS、XLSX 字典文件的真实选择、预览、错误反馈、导入、导出和刷新持久化；下方较早的“尚未接入文件流”文字仅保留为历史过程记录。
- 当前剩余项属于生产环境验收：外部邮件/短信供应商、对象存储/备份恢复、制品扫描和滚动发布、跨实例缓存、容量/故障转移压测及 CI 远端执行，不是本地平台入口或组件缺失。

## 2026-09-15 数据库基线收敛（历史记录）

- 已将原 V1–V48 合并为 `backend/src/main/resources/db/migration/V1__platform_initialization.sql` 一份 PostgreSQL 18.6 初始化 SQL；生产 Flyway 目录不再加载增量文件。历史脚本仅作为测试夹具保留在 `backend/src/test/resources/legacy-migrations.archive`。
- 本地 `lumira_next` 已先生成数据库备份，再重建并从单文件基线启动。Flyway 当前记录为 `1 platform initialization`；37 张平台表、31 条内置导航、13 条字典项和 audit-console/feedback/localization/sensitive-words 四个模块种子均通过校验。
- 新增受保护的 `pnpm db:reinitialize` 和 `pnpm db:schema-smoke`：重建命令需要显式开关并自动备份，schema smoke 检查活动 SQL 数量、表、关键约束/索引和稳定种子，不会操作旧 MySQL 数据库。
- 全局文件中心已补齐：管理员可在 `/dashboard/files-all` 按账号检索文件、下载原始字节并进行带版本校验的删除；权限、文件引用保护、BlobStore 和审计复用账号文件链路。通知中心已补齐：管理员可在 `/dashboard/notifications` 向全部或指定账号发布站内通知，列表复用 ResourceTable，发布复用 PlatformForm/FormDrawer，通知正文/链接/过期时间均由服务端校验并写入审计。
- 本地回归：后端 `clean verify` 共 256 项，失败 0、错误 0（88 项外部依赖测试按环境跳过）；前端 TypeScript、生产构建、17 项 Node 测试和全量浏览器脚本通过；schema smoke、Passkey 闸门、登录限流拒绝审计、安全策略 API/页面、Outbox 死信人工重放审批和前后端 readiness 均通过。在线升级边界已写入 [数据库初始化说明](database-initialization.md)：不能覆盖已执行的基线，生产变更需先走受评审迁移或备份重建。
- 用户管理补齐了按当前账号数据范围过滤的 CSV 导出：`GET /api/platform/users/export` 复用用户列表查询边界，使用 UTF-8 BOM、标准 CSV 转义和 `no-store` 响应；前端入口复用 ResourceTable 工具栏并保留当前搜索条件，接口和浏览器下载回归均通过。
- 模块包登记已形成声明式交接闭环：`platform_module_package` 提供摘要幂等、STAGED/ACTIVE/SUPERSEDED/REJECTED 状态、revision 并发护栏和代码版本/依赖检查；服务端不执行上传制品，制品扫描、发布、迁移和多实例滚动由部署流水线负责。
- 安全策略配置已形成闭环：`/dashboard/security` 可维护密码规则、登录/找回限流和验证码 TTL/冷却/配额/失败上限；后端通过同一份版本化 PostgreSQL 设置、审计、历史与回滚驱动认证和验证码存储，热路径使用 2 秒有界缓存。会话 Cookie 生命周期、CAPTCHA/MFA 供应商和外部验证送达仍属于运行时/后续业务边界。
- Passkey 公共适配已接入：挑战和凭据均由 PostgreSQL 统一存储，挑战按会话、TTL 原子消费，认证校验 Origin/RP ID、UP/UV、ECDSA/RSA 签名和计数器；配置默认关闭并有独立版本历史接口。硬件认证、生产域名 HTTPS、MFA 供应商和多实例压测仍未验收。
- 组织数据范围已与用户数据范围统一：新增 `platform_visible_organizations`，组织列表、父节点筛选、详情和祖先链均按账号对组织读取权限的 SELF/ORGANIZATION/SUBTREE/ALL 过滤；范围外详情返回 404。`data-scope-smoke.py`、`organizations-smoke.py`、schema smoke 和完整后端 clean verify 均通过。其他业务资源尚未声明组织归属，因此仍不能声称全资源数据范围已完成。

## 2026-09-14 V47/V48 复验（历史记录；上方 2026-09-15 复验优先）

- 短信与账号手机能力已在真实本地 PostgreSQL 应用：`platform_sms_delivery` 加密短信队列、租约令牌/虚拟线程 worker、`platform_account_phone` 手机绑定状态与一次性验证码摘要均已落库；短信配置、账号手机号请求/确认接口和短信队列运维查询已接通。审计保留任务已接入，登录限流和超长密码拒绝在识别到账号时写入安全事件。
- 短信供应商采用 HTTPS/本地回环 JSON webhook，凭据仅进入加密 SecretStore；未配置或未启用时请求明确返回 503，不创建验证码。非法控制字符密钥会被归类为终止性认证失败，不触发重试；监控聚合待发送/失败短信数量。
- 后端启用本地 PostgreSQL 夹具执行 `clean verify`：242 项测试通过，失败、错误、跳过均为 0。前端 Node 24 TypeScript 检查、Next.js 生产构建、17 项 Node 测试和全量 `test:browser` 均通过；登录限流真实回归覆盖拒绝审计，安全策略页面覆盖密码/限流/验证码字段读写、历史回滚与刷新持久化，短信专项浏览器回归覆盖配置页、账号抽屉、队列状态/筛选/搜索、错误反馈、密码清理和 390px 无溢出。
- 服务已由本次构建出的 JAR 重启，`GET http://127.0.0.1:8090/actuator/health/readiness` 返回 HTTP 200 / `UP`，前端预览仍为 `http://127.0.0.1:8010/user/login`。官网/赛事、生产外部短信送达、对象存储、备份恢复、容量压测、故障切换和动态模块代码部署流水线仍不属于已完成验收。
- 邮件/短信终态队列已接入有界保留清理（默认 30 天，终态才可删，批量 `SKIP LOCKED`）；加密正文清理不影响独立审计历史。外部渠道送达和生产多实例仍需部署环境验收。

## 2026-09-14 增量复核（覆盖下方较早记录）

- 新增 V39 平台事件 Outbox，以及 V40/V41 站内通知：事务内幂等写入、JSON 负载上限、SKIP LOCKED 租约、指数退避、死信和过期租约恢复；反馈提交/处理可通过本地化通知进入账号收件箱，顶部入口支持未读数、单条已读和全部已读。
- 新增全局在线用户会话管理：复用 Spring Session 查询有效会话，服务端分页/字面搜索/安全投影，管理员强制下线、审计记录和全局权限隔离；在线用户页面复用 ResourceTable/DeleteAction，支持刷新失败保留数据、重试和八语言文案。
- 新增平台运行监控：聚合用户/会话/模块、Outbox、邮件、通知和 24 小时审计计数；只允许全局审计读取权限，页面复用共享卡片/错误重试与八语言文案，移动端无横向溢出。
- 新增 V42 版本化动态导航：服务端菜单树、内置菜单保护、版本冲突、启停/软删除/排序、全局管理权限和运行时权限/模块过滤；树形与双层导航共用服务端快照，管理员页面支持新增、编辑、启停、排序和删除自定义菜单，八语言和移动端回归已覆盖。
- 新增 V43/V44 导航地址与组件契约：PAGE 的 sectionKey/path 由 PostgreSQL 唯一索引和服务端并发校验保护；componentKey 只允许受限标识，前端通过代码注册表精确解析，未注册键继续使用受控占位页。
- 新增 V45 敏感词策略模块：Aho-Corasick 热路径匹配、NFKC/大小写归一化、词条分页 CRUD、启停、批量导入、检查接口、全局权限/审计，以及反馈公共提交边界的阻断联动；运行日志不记录原词。
- 新增 V46 运行时本地化模块：文案键 JSONB 版本化 CRUD、启停、5,000 条 JSON 导入、语言包发布计数器、公开 bundle、短 TTL 缓存和前端静态词典回退；管理读取/写入/导入权限独立，变更写入审计。
- 新增安全动态 PAGE 入口：服务端只接受单段 `/dashboard/{slug}` 路径，Next.js 运行时菜单可进入未知但已授权的页面；未随部署注册组件时展示受控占位页，不执行数据库中的 HTML 或代码。动态 PAGE、删除清理和移动端无溢出已由浏览器回归覆盖。
- Outbox、通知、在线用户、监控、反馈、敏感词策略、运行时本地化、短信队列与账号手机验证、安全策略配置的 PostgreSQL/HTTP/浏览器回归均通过；前端类型检查、生产构建和 17 项 Node 测试通过，后端启用本地 PostgreSQL 夹具的 clean verify 为 242 项，失败/错误/跳过均为 0；导航重复地址、注册组件、敏感词、本地化、短信错误边界和登录拒绝审计回归通过。当前只完成平台边界，尚未注册赛事消费者、重放审批和外部消息总线。
- 本地服务已重启并应用单文件基线；后端 readiness HTTP 200。旧系统仍保留，官网/赛事业务迁移尚未开始。
- 2026-09-14 复验同时清理了 Ant Design 6 的运行时弃用调用：通知列表使用 `Listy`，垂直布局使用 `orientation`，统计卡片使用 `styles.content`；类型检查、生产构建及通知/监控浏览器回归通过。
- 依赖兼容性已收敛：后端不再显式覆盖 Jackson/ Spring 版本，由 Spring Boot 4.1.1 BOM 管理 `tools.jackson` 3.1.5 及其兼容注解包；新前端只保留稳定的原生 `antd@6.6.3`，不再引入预发布 Pro Components。
- V43 已补齐反馈创建表单的账号草稿适配：复用 `useAccountDraft`/`DraftActions`，使用版本化安全编解码，支持保存、取消后恢复、提交成功后按消费修订清理，并由浏览器回归验证。
- Outbox 已增加精确事件消费器注册表、虚拟线程并发 worker、租约令牌确认和 30 天已投递事件批量清理；没有注册消费器时保持事件待处理，死信不会被自动删除。
- 当前公共平台未完成项仍包括：动态模块安装/升级及其可安装组件包、生产对象存储与备份恢复、压测/故障切换、赛事/外部消费者注册、死信重放审批、通知外部渠道和短信供应商生产送达验收；审计保留策略已具备默认实现，但生产合规周期和删除审批仍需按部署要求确认。

## Latest verification supplement (supersedes older counts below)

- Current production build, TypeScript check and 17 Node tests pass. With the local PostgreSQL fixture enabled, backend `clean verify` reports 242 tests with zero failures, errors or skips. The full browser suite also passes on Node 24; readiness returns HTTP 200 after the latest restart.
- Reran data-scope, sessions and profile-completion HTTP suites: all pass.
- The full browser suite covers the registered dashboard routes and controls at desktop/mobile widths, including navigation motion, account/security, settings, files, Outbox, notifications, monitoring, online users, feedback, sensitive words and runtime localization; all pass with zero pageerror and no document horizontal overflow.
- Configurable profiles now include built-in field rendering, five custom types, dictionary choices, reference protection and weighted completion. Older outstanding references to these entire features below are superseded. Avatar and verified email are complete; phone verification is now implemented by V47, while external SMS delivery remains unverified.
- Completion rounding finding fixed: incomplete scores are capped at 99 percent for both overall and group totals. Maven verify now passes 51 tests; rebuilt/restarted local services and real HTTP boundary regression confirms 200/201 yields 99 and fully filled yields 100.
- User-facing account files are now wired locally: bounded upload, quota accounting, metadata/list/download, versioned delete, normalized inline images and avatar replacement all use the shared BlobStore path and are covered by HTTP/browser checks. The implementation is still local-disk storage; production object storage, cross-instance consistency and backup/restore remain unverified.
- Navigation now has a server-owned versioned tree with runtime permission filtering and an admin editor. Safe dynamic PAGE slugs resolve through the same tree; V43 prevents duplicate live addresses and V44 carries a constrained componentKey into a code-defined registry, rendering a controlled placeholder when a key is not bundled. V45 adds a versioned sensitive-word policy module with shared matching and feedback blocking. V46 adds a versioned runtime localization catalog with public bundle fallback. Dynamic module installation, external message channels, replay approval, complete recovery lifecycle and domain-specific consumers remain incomplete.
- Production containers, backup recovery, load and failover remain unverified. Local health and browser success do not establish production capacity or availability.

以下为 2026-09-09 的历史基线记录；上方 2026-09-14 增量复核覆盖其中较早的计数和验证结论。对象：lumira-next 新脚手架，本地前端 8010、后端 8090。

**结论：尚未全部完成。已有公共平台功能可用于开发验证，但不满足旧系统全量替换和高并发、高可用交付条件。旧项目应保留。**

## 功能清单

| 能力 | 当前实现 | 剩余事项 |
| --- | --- | --- |
| 身份与账号 | 登录退出、密码修改、基本个人资料、本人会话管理、数据库登录限流、头像、邮箱和手机验证 | 完整账号生命周期、外部短信/邮件收件箱和安全事件保留策略 |
| 用户、角色、组织 | 管理页面、分页搜索、启停、角色授权、组织树、防循环、版本冲突保护、用户数据四种范围 | 全资源数据范围、受限管理员委派和完整生命周期验收 |
| 导航与偏好 | 树形和双层导航、二级内分组、固定/自动隐藏、平滑动效、文字切换、主题、八语言 | 触控设备、跨标签偏好同步和全部页面组合仍需扩大验收；动态菜单和翻译管理已接入 |
| 字典 | 类型与条目、树形结构、启用查询、JSON 导入导出、TXT/XLS/XLSX 预览导入、摘要校验 | 完整导入统计、容量验证、动态资料字段消费 |
| 公共配置 | 品牌、协议、水印、维护策略和安全策略；分区保存、版本历史与回滚；登录页及后台消费配置 | 生产外部发布、跨实例缓存和完整受限管理员矩阵仍需验收（四类配置只读字段/历史/回滚与直接写入拒绝已验收） |
| 水印与品牌细节 | 文本/图片配置、个人变量、名称/标识/背景/页脚配置 | 图片及移动端叠层验收；邮箱/手机变量的生产渠道接通；配置跨标签传播 |
| 审计 | 已接入操作的记录和分页查询 | 全事件覆盖、变更前后差异、保留策略 |
| 平台模块 | 两个静态模块及依赖校验 | 动态安装、升级、禁用、兼容检查 |
| 文件与消息 | 账号文件、配额、下载/版本删除和头像已接入本地 BlobStore；邮件 SMTP 队列、短信 webhook 配置和加密投递队列已接入 | 生产对象存储、跨实例一致性、备份恢复、外部邮件/短信渠道及生产投递验收 |
| 异步任务与工具 | Outbox 生命周期与运行监控已接入；敏感词策略已接入公共提交边界 | 调度/消费者、死信重放审批、保留清理 |
| 官网与赛事 | 尚未完成迁移 | 官网赛事展示、发布同步和业务闭环，需在底座后继续实施 |
| 运行与交付 | 本地服务健康、编译、生产构建、API/schema/browser 回归通过 | 真实容器链路、生产数据迁移、备份恢复、容量压测和故障切换 |

## 本轮实际验证

- 后端健康接口返回 HTTP 200。
- TypeScript 检查与 17 项 Node 测试通过；后端最新 clean verify 结果以顶部 2026-09-15 复验的 242 项为准，失败/错误/跳过均为 0。
- 真实 HTTP：品牌版本/历史/回滚与 CSRF；维护登录拒绝及会话清理、允许角色和过期策略；四种用户数据范围及越权拒绝；会话隔离、撤销及重放拒绝，全部通过。
- 独立浏览器登录后访问全部 11 个后台路由，均能加载 workspace 外壳，未记录 pageerror，1213×886 下未检测到文档横向溢出。这是路由外壳冒烟检查，不代表每页数据、全部按钮或导航动效验收。
- 前端包已声明 ESM，当前 Node 测试不再输出 MODULE_TYPELESS_PACKAGE_JSON 警告。

## 本轮发现的运行版本差异

后续修复已重启本地应用并应用 V14。用户停用及角色修改/分配共用数据库恢复能力规则，配置恢复要求有效账号同时拥有全局配置写权限和配置读取权限。事务回滚式 SQL 回归覆盖唯一恢复账号、双管理员冗余、缺少读取权限、停用账号、角色范围收缩和角色禁用；用户、角色和维护策略 HTTP 回归通过。SQL 场景验证不等于所有浏览器管理员操作组合均已验收。

## 历史证据与边界

platform-migration.md 记录字典三种文件浏览器操作、品牌保存及冲突/回滚、协议展示、水印文本渲染、维护角色跨页选择等历史通过证据。本轮未重跑这些完整浏览器套件，不能将历史记录当成本轮全量验收。

检查后的修复已完成本地 V14 迁移，未部署到远端。远端 CI、生产环境、高并发容量和高可用故障恢复均未获验证。

## 建议推进顺序

1. 对齐运行版本，验收最后管理员保护与权限、导航关键路径。
2. 补齐动态菜单、资料字段、文件和消息能力，再补异步任务与公共工具。
3. 执行完整浏览器回归、容器及备份恢复演练、容量压测与故障切换验收，然后迁移业务和正式切换。
