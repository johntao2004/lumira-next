## 开发入口

目录职责、独立 SDK 与构建见 [框架开发约束](docs/framework-development.md)，演进门槛见 [路线图](docs/evolution-plan.md)，生产交付见 [准入清单](docs/production-readiness.md)。编码参考：[后端规范](docs/reference/backend-standard.md)、[前端规范](docs/reference/frontend-standard.md)。核心双语注释清单见 docs/core-file-inventory.json。

# Lumira 新脚手架

与旧系统并行开发的独立底座。前端 8010，后端 8090，数据库 lumira_next；旧前端 8000 与旧后端不受影响。

新克隆仍由 `scripts/init-local.mjs` 生成默认 `.runtime/local.json` 并使用 `lumira_next`；历史本地配置不会被自动发现或合并。接管已运行服务后，使用 canonical 配置和运行时 pin，并按[本地启动与插件清单换库护栏](docs/database-initialization.md#本地启动与换库插件清单护栏)检查启动和数据库切换。启动、状态和停止入口分别是 `node scripts/start.mjs`、`node scripts/status.mjs`、`node scripts/stop.mjs`。

换电脑后的分支构建、本地配置和重复验收步骤见[跨机器测试说明](docs/cloud-development.md)。

生产 Compose 新卷使用独立数据库 bootstrap、迁移与非超级业务角色，必填密钥环；已有卷需先核验与迁移角色，见[数据库部署角色](docs/database-deployment-roles.md)。

## 技术基线

Umi Max 4.7.20 / Mako / React 19.2.8 / Ant Design 6.6.3 / TypeScript 7.0.2；Java 25 LTS / Spring Boot 4.1.1 / PostgreSQL 18.6（“大象数据库”）。当前是本机 Homebrew PostgreSQL，不是 ElephantSQL 云服务；生产可使用兼容的托管 PostgreSQL。前端依赖固定版本，pnpm 锁文件纳入版本控制，后端优先使用 Boot BOM。

## 启动

Codex 云端开发使用独立 Ubuntu 或 Debian 环境，安装入口为 `LUMIRA_CLOUD_DEV=1 bash scripts/cloud-install.sh`，任务启动入口为 `bash scripts/cloud-start.sh`。完整配置见 [云端开发说明](docs/cloud-development.md)。下列 Homebrew 命令用于本机 macOS。

前置：原生 Node.js 24 LTS、JDK 25、PostgreSQL 18、pnpm 10.33.0，以及运行中的 Docker Engine 和 Docker Compose v2。当前用户必须能访问 Docker daemon。DOC/DOCX/XLS/XLSX 解析只在 Docker Office worker 中执行；Docker 不可用时本地和云端启动均会失败，不会回退到宿主 JVM。macOS 可用 Homebrew 安装系统运行时；Node 24 是 keg-only，需将它放在 PATH 前端：

```sh
brew install node@24 openjdk@25 postgresql@18
export PATH="$(brew --prefix node@24)/bin:$PATH"
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
```

`scripts/start.mjs` 要求当前 Node 主版本为 24，并使用当前系统 Node 与 `JAVA_HOME` 启动服务。它会在 `.runtime/office-worker/` 单独生成权限受限的 worker token，启动内部隔离网络中的 Office worker，并通过固定入口仅向回环地址提供访问；worker 地址和 token 只传给后端进程，不会改写 `.runtime/local.json`。`scripts/stop.mjs` 只停止该本地 Compose 项目。项目不携带 Node/JDK 工具链。

```sh
pnpm install
pnpm typecheck
pnpm build
pnpm plugins:build
pnpm test:java
# 创建独立 PostgreSQL 角色/数据库，仅首次需要
PSQL_BIN=/opt/homebrew/opt/postgresql@18/bin/psql node scripts/init-local.mjs
# 首次登录前生成会话密钥。缺少时登录返回 503 secretKeyUnavailable
node scripts/init-secret-key.mjs
# 服务首次启动会由 Flyway 自动导入单文件基线
node scripts/start.mjs
node scripts/stop.mjs
```

本地默认使用 Umi Max 开发服务器和 Mako 增量构建。完成 `pnpm build` 后，可用 `LUMIRA_WEB_MODE=production node scripts/start.mjs` 验证 `apps/web/dist` 静态产物。

开发预览的分包输出到 `.runtime/web-dev`，生产构建输出到 `apps/web/dist`，两者独立，运行 `pnpm build` 不会清理正在使用的开发分包。`pnpm typecheck` 在项目预览运行时复用其 Umi 生成文件；预览未运行时自动执行 setup。手动执行 `pnpm --dir apps/web setup` 前应停止开发预览，因为 Umi setup 会清空并重建开发生成目录。

数据库管理员也可以直接导入同一份 SQL；导入后首次启动需要让 Flyway 建立基线记录：

```sh
psql -v ON_ERROR_STOP=1 -1 -d lumira_next -f backend/src/main/resources/db/migration/B52__platform_initialization.sql
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true SPRING_FLYWAY_BASELINE_VERSION=52 node scripts/start.mjs
```

空库初始化会在 B52 中创建“管理员”和“普通用户”两个角色，但不会预置普通账号、业务记录或直授权限。服务首次启动只创建 `admin` 引导账号，并通过“管理员”角色获得管理权限；“普通用户”角色为 SELF 数据范围，仅含工单反馈的访问、读取和创建权限，作为注册缺省角色（注册仍默认关闭）。随机引导密码保存在本机 `.runtime/local.json` 的 `LUMIRA_BOOTSTRAP_PASSWORD`。文件权限 0600，仅在本机查看，不提交或发布。初始管理员首次登录必须先设置新密码；改密成功后登录流程会继续，按需完成 CAPTCHA 或 MFA，不必重新输入新密码。刷新可继续改密，返回登录页不会解除要求。重启不会重设已有账号密码。完整流程见[数据库初始化说明](docs/database-initialization.md)。

全局数据库只保留 `backend/src/main/resources/db/migration/B52__platform_initialization.sql` 一份初始化脚本，包含完整结构、约束、索引、函数、视图和稳定种子。Flyway 只扫描 `classpath:db/migration`，空库首次启动执行这份基线，重复启动只校验已有 B52 记录。旧增量脚本、兼容升级目录和历史 SQL 测试归档已删除，不提供旧版本数据库升级。已有 B52 本地库的保数据切换方案与限制见[数据库初始化说明](docs/database-initialization.md)；不能通过关闭校验或 repair 伪装兼容。插件各自保留一份最终初始化 SQL，旧安装保留并严格校验原迁移记录，详见[插件初始化契约](docs/plugin-initialization.md)。初始化不包含账号、业务记录、密码或其他秘密。

需要重建本地数据库时先停止服务，再执行 `LUMIRA_ALLOW_LOCAL_DB_RESET=1 pnpm db:reinitialize`。命令必须带显式的本地重置开关，会先生成 `.runtime/backups/*.dump` 备份，并且只重建配置中的 `lumira_next`，不会触碰旧系统数据库。重建后启动服务会执行 B52 单文件初始化；`pnpm db:schema-smoke` 会检查唯一初始化脚本、表/约束/索引和种子完整性。数据库结构与维护约定见 [数据库初始化说明](docs/database-initialization.md)。

后端只连接独立 PostgreSQL 数据库；不会导入或删除旧库数据。默认数据库会话 30 分钟，不持久化浏览器凭据。当前登录不支持旧系统账号。

## 验证

```sh
pnpm check:java-format
pnpm test
pnpm test:java
pnpm db:schema-smoke
```

`pnpm db:schema-smoke` 在数据库迁移完成后检查结构和种子，只执行只读查询。`pnpm test:module-packages`、`pnpm test:plugin-data`、`pnpm test:browser` 以及 `scripts/smoke.py`、`scripts/passkey-smoke.py` 等共享 `smoke_support.py` 的测试会改动 fixture 数据，必须使用显式私有配置和带 QA 标记的专用数据库，不可用默认 `.runtime/local.json`。配置、marker、服务绑定与浏览器测试要求见[QA fixture guard 说明](docs/qa-fixture-guard.md)和[跨机器测试说明](docs/cloud-development.md)。

命名、目录职责、CRUD 复用与自动质量检查见 [源码约定](docs/code-conventions.md)。`pnpm check:java-format` 使用 Spotless 全量检查宿主后端与独立插件后端的 Java 源码；`pnpm check:quality` 检查命名和新增重复代码，CI 与 `pnpm test` 均执行此检查。

范围、接口及后续切换条件见 [架构与迁移说明](docs/architecture.md)。当前交付为可运行底座第一阶段，不是全量业务迁移结果，不应删除旧系统。

算法选择、复杂度、密码升级策略和“最新最优”的验证边界见 [算法基线](docs/algorithm-baseline.md)。

平台运维页面提供在线用户会话管理（`/dashboard/online-users`）和只读运行监控（`/dashboard/monitoring`）。前者复用 Spring Session 并要求全局用户权限，后者聚合用户、会话、模块、Outbox、邮件、通知和审计计数并要求全局审计读取权限；两者均支持服务端分页/权限隔离、刷新失败重试、八语言和移动端布局。

管理员全部文件中心（`/dashboard/files-all`）按所属账号检索平台文件，复用 BlobStore 流式下载、版本化删除、文件引用保护和审计；通知中心（`/dashboard/notifications`）支持向全部或指定账号发布站内通知，使用统一 PlatformForm/FormDrawer/ResourceTable 和独立的全局通知权限；发布确认表示事件已入队，通知随后异步进入收件箱。邮件和短信仍通过各自的持久化投递队列处理，不把外部渠道凭据写入通知记录。

我的文件与管理员全部文件中心共用在线预览，支持图片、分页 PDF、Word 文字和常见文本格式，详见 [文件在线预览](docs/file-preview.md)。

模板中心支持上传 Word/Excel 空表、绑定用户字段并生成 PDF，用户管理支持 Excel 导入与导出。模板发布版本、权限、文件限制和隔离转换依赖见[模板中心与用户数据传输](docs/template-center.md)。

顶栏个人通知弹层默认展示未读通知，可切换已读通知并分别分页。`GET /api/account/notifications` 支持可选的 `read=false`（仅未读）和 `read=true`（仅已读）；未提供 `read` 时保留原有 `unread=true` 仅未读、`unread=false` 全部通知的语义。筛选在服务端分页前执行，两类列表都只返回当前账号尚未过期的通知。

Outbox 提供按事件类型显式注册的消费器边界、虚拟线程并发租约 worker 和已投递记录保留清理；没有注册消费器时不会自动确认事件。全局事件权限可在 `/dashboard/event-outbox` 对死信执行必填原因的人工重放，审批历史单独落库并写入审计；通知广播和工单反馈已接入事务 Outbox 与真实收件箱消费者；外部消息总线和生产故障切换仍需独立接入与验收。

审计流提供全局分页查询、账号安全记录和来源上下文；登录限流/超长密码拒绝在识别到账号时也会写入安全事件。`AuditRetention` 按可配置保留期以有界批次清理过期审计，避免高并发下审计表无限增长；默认保留 365 天，生产需结合合规要求设置 `LUMIRA_AUDIT_RETENTION`。

安全策略（`/dashboard/security`）统一维护密码规则、登录/找回密码的账号与来源限流，以及验证码有效期、冷却时间、请求配额和失败上限。配置使用 PostgreSQL 持久化、版本冲突校验和审计，安全策略页只提供保存操作；认证与验证码存储读取同一权威数据库策略，不使用进程本地策略缓存；安全与 Passkey 策略的已提交变更对后续读取可见，回滚不会污染后续读取。TOTP 二次认证通过独立热插拔插件提供，不伪装成短信/邮件供应商配置；绑定、恢复、管理员重置和 fail-closed 边界见 [二次认证说明](docs/mfa.md)。

导航管理（`/dashboard/navigation`）使用基线中的版本化菜单树作为 ProLayout 树形菜单的运行时来源，支持内置菜单保护、自定义分组、排序、启停、软删除、版本冲突和按权限/模块过滤。PAGE 标识与路径有数据库唯一约束，componentKey 只解析到前端代码注册表；安全的自定义 PAGE 可进入 `/dashboard/{slug}`，未注册组件时展示受控占位页，不执行数据库 HTML 或代码。模块页支持完整插件包导入、依赖与冲突预检、在线安装/卸载和状态版本护栏。工单反馈、本地化、敏感词和 TOTP 二次认证位于根目录 plugins/，使用 PF4J/Spring 子容器和前端动态注册，无需重构建或重启宿主；详见 [插件开发与运行](docs/plugins.md)。

敏感词策略（`/dashboard/sensitive-words`）提供角色管控、共享词库、命中记录和独立策略试运行。所有角色共用词库，角色只配置是否管控，词条决定阻断或仅记录；服务端在业务 JSON 文本及上传文件名提交时统一执行，多个有效角色中任一启用即管控。管理权限独立校验，普通账号不能读取词库，命中记录不保存提交原文。具体边界与默认策略见 [全局敏感词管控](docs/content-policy.md)。

运行时本地化插件（`/dashboard/localization`）接管平台基线数据，提供文案键的版本化 CRUD、JSON 批量导入、启停、发布版本和公开语言包读取。后台文案保存后由短 TTL 缓存原子刷新，发布记录可追踪版本；前端 Provider 在静态八语言词典之上加载覆盖，接口不可用时自动回退静态文案。

账号手机与短信（平台基线能力）由 `/dashboard/sms`、`/dashboard/sms-deliveries` 和账号设置的“账号安全”分类提供。短信供应商配置只保存非敏感字段，凭据进入加密 SecretStore；验证码先写入加密的 PostgreSQL 投递队列，再由带租约令牌的虚拟线程 worker 按腾讯云、阿里云中国站/国际站、华为云、Twilio 的官方协议发送，后台兼容历史 JSON webhook。表单按供应商和国内/国际范围展示字段，自动填入服务地址、模板参数及签名所需数据；账号密钥、签名和审核模板由管理员提供。手机号绑定要求当前密码、E.164 格式、冷却/日上限/失败次数和一次性验证码；未配置短信服务时明确返回不可用，不生成半成品验证码。运维人员可按状态和任务 ID查看脱敏队列元数据，运行监控同步展示待发送与失败数量，凭据和验证码不会进入日志或接口响应。

短信队列与手机号验证的接口、状态机和验收边界见 [短信投递说明](docs/sms-delivery.md) 与 [账号手机号说明](docs/account-phone.md)。

### 多语言

新脚手架支持简体中文、繁体中文、英语、日语、韩语、法语、西班牙语和德语。登录页与后台共用语言菜单；未登录时保存在当前浏览器，登录后用户主动选择的语言会保存到账号，其他浏览器登录或刷新时读取已保存的账号偏好。账号尚无偏好时沿用当前浏览器缓存且不自动写入账号。Ant Design 内置文案、表单校验、导航、表格及错误页同步切换。用户名称与业务数据保留原始内容，接口与缺省行为见 [账号语言偏好](docs/account-locale.md)。

词典位于 `apps/web/src/platform/i18n/messages.ts`；平台管理员可在本地化页面维护运行时覆盖。新增语言需同时配置 `providers.tsx` 的 Ant Design locale、后端受支持语言集合，并运行 `pnpm test` 检查翻译键与占位符完整性。

### 后台导航

后台壳层使用 ProLayout 树形菜单呈现工作空间、AI 中心、用户与权限、内容与资源、消息与反馈、支付管理、运维和平台配置。菜单顺序、权限、模块状态和运行时动态页面由服务端导航树决定；前端 feature/plugin manifest 声明静态路由所属分组及权限。宽屏使用标准侧栏，767px 及以下使用 ProLayout 官方抽屉。分组归属、动态页面和权限行为见 [导航组织说明](docs/navigation-organization.md)。

主题、语言、通知和账户操作统一放在内容区顶栏，侧栏底部只保留展开/收起控制。新业务页面应在所属 `features/<module>/manifest.ts` 声明路由、权限和依赖，并在同模块 `module.tsx` 提供懒加载页面；不再修改中央导航分组表。头像菜单仅提供个人资料和退出；个人资料页内部导航包含账号安全、角色预览、条件显示的微信绑定、修改密码和我的会话。个人资料位于 `/dashboard/account-security`，认证安全页位于 `/dashboard/account-authentication-security`；旧个人资料地址 `/dashboard/account-profile` 会跳转到新地址。修改密码验证原密码并注销该用户的已有会话。

账户修改密码回归：`python3 scripts/password-smoke.py` 使用自动清理的临时账号验证，不修改管理员密码。

可观测性、HTTP 安全头、备份恢复演练、供应链扫描与架构护栏见 [运维手册](docs/operations.md)。
