# Lumira 新脚手架

与旧系统并行开发的独立底座。前端 8010，后端 8090，数据库 lumira_next；旧前端 8000 与旧后端不受影响。

## 技术基线

Umi Max 4.7.20 / Mako / React 19.2.8 / Ant Design 6.6.3 / TypeScript 7.0.2；Java 25 LTS / Spring Boot 4.1.1 / PostgreSQL 18.6（“大象数据库”）。当前是本机 Homebrew PostgreSQL，不是 ElephantSQL 云服务；生产可使用兼容的托管 PostgreSQL。前端依赖固定版本，pnpm 锁文件纳入版本控制，后端优先使用 Boot BOM。

## 启动

前置：原生 Node.js 24 LTS、JDK 25、PostgreSQL 18、pnpm 10.33.0。macOS 可用 Homebrew 安装系统运行时；Node 24 是 keg-only，需将它放在 PATH 前端：

```sh
brew install node@24 openjdk@25 postgresql@18
export PATH="$(brew --prefix node@24)/bin:$PATH"
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
```

`scripts/start.mjs` 要求当前 Node 主版本为 24，并使用当前系统 Node 与 `JAVA_HOME` 启动服务。项目不携带 Node/JDK 工具链。

```sh
pnpm install
pnpm typecheck
pnpm build
./backend/mvnw -f backend/pom.xml verify
# 创建独立 PostgreSQL 角色/数据库，仅首次需要
PSQL_BIN=/opt/homebrew/opt/postgresql@18/bin/psql node scripts/init-local.mjs
# 服务首次启动会由 Flyway 自动导入单文件基线
node scripts/start.mjs
node scripts/stop.mjs
```

本地默认使用 Umi Max 开发服务器和 Mako 增量构建。完成 `pnpm build` 后，可用 `LUMIRA_WEB_MODE=production node scripts/start.mjs` 验证 `apps/web/dist` 静态产物。

数据库管理员也可以直接导入同一份 SQL；导入后首次启动需要让 Flyway 建立基线记录：

```sh
psql -d lumira_next -f backend/src/main/resources/db/migration/V1__platform_initialization.sql
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true SPRING_FLYWAY_BASELINE_VERSION=1 node scripts/start.mjs
```

初始化账号 admin；随机密码保存在 .runtime/local.json 的 LUMIRA_BOOTSTRAP_PASSWORD。文件权限 0600，请在本机查看，不提交或发布。重启不会重设已有账号密码。

生产运行时扫描 `backend/src/main/resources/db/migration/`：`V1__platform_initialization.sql` 是由原 V1–V48 最终结构合并而成的不可变基线；`V2__identity_argon2_password_hash.sql` 扩大密码摘要字段以容纳版本化 Argon2id；`V3__group_platform_navigation.sql` 按用户、内容、运营和平台设置整理内置导航。迁移不写入管理员密码、短信/邮件密钥或其他秘密；原历史增量脚本仅保存在 `backend/src/test/resources/legacy-migrations.archive`，供升级行为回归测试使用，不会被 Flyway 加载。

需要重建本地数据库时先停止服务，再执行 `LUMIRA_ALLOW_LOCAL_DB_RESET=1 pnpm db:reinitialize`。命令必须带显式的本地重置开关，会先生成 `.runtime/backups/*.dump` 备份，并且只重建配置中的 `lumira_next`，不会触碰旧系统数据库。重建后启动服务即可按顺序执行 V1、V2、V3；`pnpm db:schema-smoke` 会检查活动迁移、表/约束/索引和种子完整性。数据库结构与维护约定见 [数据库初始化说明](docs/database-initialization.md)。

后端只连接独立 PostgreSQL 数据库；不会导入或删除旧库数据。默认数据库会话 30 分钟，不持久化浏览器凭据。当前登录不支持旧系统账号。

## 验证

```sh
pnpm test
pnpm test:module-packages
pnpm test:browser
pnpm db:schema-smoke
python3 scripts/smoke.py
```

smoke.py 在本地新底座验证匿名拒绝、CSRF、错误密码、正确登录、模块列表、用户摘要与退出后失效。它读取本地随机凭据，不输出秘密。

范围、接口及后续切换条件见 [架构与迁移说明](docs/architecture.md)。当前交付为可运行底座第一阶段，不是全量业务迁移结果，不应删除旧系统。

算法选择、复杂度、密码升级策略和“最新最优”的验证边界见 [算法基线](docs/algorithm-baseline.md)。

平台运维页面提供在线用户会话管理（`/dashboard/online-users`）和只读运行监控（`/dashboard/monitoring`）。前者复用 Spring Session 并要求全局用户权限，后者聚合用户、会话、模块、Outbox、邮件、通知和审计计数并要求全局审计读取权限；两者均支持服务端分页/权限隔离、刷新失败重试、八语言和移动端布局。

管理员全部文件中心（`/dashboard/files-all`）按所属账号检索平台文件，复用 BlobStore 流式下载、版本化删除、文件引用保护和审计；通知中心（`/dashboard/notifications`）支持向全部或指定账号发布站内通知，使用统一 PlatformForm/FormDrawer/ResourceTable 和独立的全局通知权限。邮件和短信仍通过各自的持久化投递队列处理，不把外部渠道凭据写入通知记录。

Outbox 提供按事件类型显式注册的消费器边界、虚拟线程并发租约 worker 和已投递记录保留清理；没有注册消费器时不会自动确认事件。全局事件权限可在 `/dashboard/event-outbox` 对死信执行必填原因的人工重放，审批历史单独落库并写入审计；赛事消费者、外部消息总线和生产故障切换仍需独立接入与验收。

审计流提供全局分页查询、账号安全记录和来源上下文；登录限流/超长密码拒绝在识别到账号时也会写入安全事件。`AuditRetention` 按可配置保留期以有界批次清理过期审计，避免高并发下审计表无限增长；默认保留 365 天，生产需结合合规要求设置 `LUMIRA_AUDIT_RETENTION`。

安全策略（`/dashboard/security`）统一维护密码规则、登录/找回密码的账号与来源限流，以及验证码有效期、冷却时间、请求配额和失败上限。配置使用 PostgreSQL 版本历史、审计和回滚，认证与验证码存储读取同一策略，登录热路径使用 2 秒有界缓存。会话 Cookie 生命周期与 CAPTCHA/MFA 供应商没有伪造配置项，需接入实际运行时基础设施后单独验收。

导航管理（`/dashboard/navigation`）使用基线中的版本化菜单树作为双层/分区导航的运行时来源，支持内置菜单保护、自定义分组、排序、启停、软删除、版本冲突和按权限/模块过滤。PAGE 标识与路径有数据库唯一约束，componentKey 只解析到前端代码注册表；安全的自定义 PAGE 可进入 `/dashboard/{slug}`，未注册组件时展示受控占位页，不执行数据库 HTML 或代码。模块页提供声明式模块包登记、摘要幂等、代码版本/依赖检查、激活/拒绝和乐观版本护栏；仍需部署侧完成制品发布、数据库变更和多实例滚动升级，运行进程不会执行上传代码。反馈创建抽屉复用账号级草稿保存/恢复/消费修订清理。

敏感词策略（`/dashboard/sensitive-words`）由平台基线提供分页管理、批量导入、启停和文本检查；后端使用不可变 Aho-Corasick 匹配快照，反馈公共提交在持久化前执行阻断，匹配结果仅返回脱敏片段。该模块受独立全局权限和审计约束，普通账号不会读取词典。

运行时本地化（`/dashboard/localization`）由平台基线提供文案键的版本化 CRUD、JSON 批量导入、启停、发布版本和公开语言包读取。后台文案保存后由短 TTL 缓存原子刷新，发布记录可追踪版本；前端 Provider 在静态八语言词典之上加载覆盖，接口不可用时自动回退静态文案。

账号手机与短信（平台基线能力）由 `/dashboard/sms`、`/dashboard/sms-deliveries` 和账号菜单入口提供。短信供应商配置只保存非敏感字段，凭据进入加密 SecretStore；验证码先写入加密的 PostgreSQL 投递队列，再由带租约令牌的虚拟线程 worker 发送到 HTTPS/本地回环 JSON webhook。手机号绑定要求当前密码、E.164 格式、冷却/日上限/失败次数和一次性验证码；未配置短信服务时明确返回不可用，不生成半成品验证码。运维人员可按状态和任务 ID查看脱敏队列元数据，运行监控同步展示待发送与失败数量，凭据和验证码不会进入日志或接口响应。

短信队列与手机号验证的接口、状态机和验收边界见 [短信投递说明](docs/sms-delivery.md) 与 [账号手机号说明](docs/account-phone.md)。

### 多语言

新脚手架支持简体中文、繁体中文、英语、日语、韩语、法语、西班牙语和德语。登录页与后台共用语言菜单，选择保存在当前浏览器，刷新后保留；Ant Design 内置文案、表单校验、导航、表格及错误页同步切换。用户名称与业务数据保留原始内容。

词典位于 `apps/web/src/platform/i18n/messages.ts`；平台管理员可在本地化页面维护运行时覆盖。新增语言需同时配置 `providers.tsx` 的 Ant Design locale、后端受支持语言集合，并运行 `pnpm test` 检查翻译键与占位符完整性。

### 双层导航

后台桌面壳层固定采用双层侧边导航：80px 一级模块栏负责切换业务域，224px 二级菜单展示当前模块的页面路由。二级菜单使用 Ant Design `Menu` 的选中、悬停和键盘行为；一级与二级均沿用服务端顺序、权限过滤、模块状态和运行时动态页面。宽屏默认展开，768–991px 默认收起为一级栏并可手动展开，767px 及以下使用 ProLayout 官方抽屉，避免压缩正文或产生横向溢出。

主题、语言、通知和账户操作统一放在内容区顶栏，侧栏底部只保留展开/收起控制。新业务页面应在所属 `features/<module>/manifest.ts` 声明路由、权限和依赖，并在同模块 `module.tsx` 提供懒加载页面；不再修改中央导航分组表。账户菜单继续提供个人资料、会话、草稿、修改密码和退出；修改密码验证原密码并注销该用户的已有会话。

账户修改密码回归：`python3 scripts/password-smoke.py` 使用自动清理的临时账号验证，不修改管理员密码。
