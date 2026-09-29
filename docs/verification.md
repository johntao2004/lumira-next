# 第一阶段验证记录

## 2026-09-22 Umi Max + Mako 前端迁移复验（最新）

- 活动前端已从 Next.js 16/Tailwind 迁移到 Umi Max 4.7.20 + Mako 0.11.10，继续使用 React 19.2.8、Ant Design 6.6.3、Pro Components 与 TypeScript 7.0.2；Spring Boot、PostgreSQL、`/api`、Cookie/CSRF 和现有 `/dashboard/{section}` 地址未改动。
- 项目内 Node 24.20.0 执行 `pnpm typecheck`、`pnpm build` 均通过；构建日志明确为 `Building with mako for production`，静态产物位于 `apps/web/dist`，不再生成或运行 `.next/standalone`。
- `pnpm test` 21/21 通过；边界测试现在拒绝重新引入 Next 运行时，同时忽略 Umi 自动生成的 `src/.umi`。
- `pnpm test:browser` 全量串行通过，覆盖草稿、模块、动态导航、认证与账户、设置、品牌、协议、水印、维护、资料、文件、通知、邮件、短信、Outbox、监控、用户、反馈、敏感词和本地化；桌面/390px 移动端、亮暗主题、八语言、页面错误与横向溢出均在真实浏览器中检查。
- 生产静态预览已在 8010 启动并通过深链接 `/dashboard/watermark`、登录态刷新和 `/api/auth/csrf` 代理验证；后端 8090 readiness 为 HTTP 200。静态 HTML 在应用正文创建前恢复主题/语言，公开品牌配置会立即刷新而不把内置默认值误当成 30 秒有效数据。
- 容器前端改为 Caddy 托管 `dist` 并使用 SPA fallback；生产边缘 Caddy 仍负责将 `/api` 转发给 Spring Boot。此记录只证明本地构建、静态预览和浏览器回归，不代表生产部署、容量或高可用证明。

## 2026-09-16 JDK 25 与模块化算法闭环复验（历史记录）

- `pnpm db:schema-smoke` 通过：活动迁移为不可变 V1 基线加身份模块 V2 追加迁移，37 张平台表、31 条确定性内置导航、13 条字典项、关键唯一索引和状态约束完整；密码摘要列已扩展到 256 个字符。
- 后端使用 Homebrew OpenJDK 25.0.4.1 执行 `./mvnw -q verify` 通过：Surefire 共 271 项，失败 0、错误 0、环境跳过 91 项，并生成可运行 JAR。Mockito 使用显式 Java agent，避免 JDK 25 动态自附加。
- 前端所有检查使用项目内 Node 24.20.0：`pnpm test`（21/21）、`pnpm typecheck`、`pnpm build` 全部通过；Next.js 16.3.4 生产构建成功。
- `pnpm test:browser` 全量通过，覆盖导航、认证、账户、设置、资料字段/值、文件、通知、邮件、短信、监控、用户、反馈、敏感词和本地化等场景；此前发现的异步滚动测试等待窗口和资料内置标签问题均已修复。
- `python3 scripts/smoke.py` 通过：匿名拒绝、CSRF、错误登录、正确登录、当前用户、模块注册、用户安全字段、权限撤销/恢复、退出后旧 Cookie 重放拒绝；数据库实测确认旧 BCrypt 登录后已升级为 Argon2id。
- 服务已使用 JDK 25 启动并通过 readiness/登录联调；启动时 Flyway 校验 V1+V2，当前数据库版本为 2。
- 模块边界检查已扩展为 15 个前后端一致的模块：platform 仅保留壳与生命周期，字典、文件、设置、目录、事件、导航、通知、监控和通信均有独立描述符、依赖与前端懒加载入口；48 个 Controller 的归属均被静态校验。
- 模块边界检查同时验证后端依赖图的缺失依赖、环依赖和必选/可选约束；`AdministrationGuard` 的设置/模块共享锁、权限域分桶锁和文件管理纯读路径均已通过源码复核，分桶规则另有 3 项单元测试。
- `backend/src/main/resources/db/module-ownership.json` 已覆盖 V1 的 37 张平台表、3 个视图和 5 个函数，作为数据库模块化拆分前的可验证归属层；本次没有修改已执行的 V1。

## 2026-09-15 数据库初始化复验（历史记录）

- PostgreSQL 18.6 独立数据库 `lumira_next` 已先备份再重建，Flyway 只执行 `V1__platform_initialization.sql`，当前历史表为 `1 platform initialization`。
- `pnpm db:schema-smoke` 通过：活动迁移文件为 1 个，37 张平台表、31 条内置导航、13 条字典项、关键唯一索引和状态约束完整。
- 直接 `psql -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/migration/V1__platform_initialization.sql` 已在临时 PostgreSQL 18 空库成功执行；脚本不包含管理员密码或渠道密钥。
- `pnpm db:reinitialize` 具备显式开关、自动备份和旧库隔离；在线生产升级不能覆盖已执行的基线，维护边界见 [数据库初始化说明](database-initialization.md)。
- `/dashboard/security` 与 `/api/platform/settings/security` 已通过安全策略专项回归：密码规则、登录/找回限流、验证码 TTL/冷却/配额/失败上限均可版本化保存、审计、历史查看和回滚；认证与验证码存储读取同一份策略。
- 模块包专项回归通过：声明式 manifest 登记、规范化摘要、重复登记幂等、未知模块激活拒绝、拒绝状态和过期 revision 均由 HTTP smoke 覆盖；浏览器覆盖模块包抽屉登记、代码未部署提示和拒绝。服务端只保存部署交接元数据，不下载或执行制品。
- Passkey 专项边界回归通过：默认关闭、CSRF 保护、未启用时认证/注册拒绝、认证后凭据列表安全投影均通过；硬件凭据注册/认证需在 HTTPS 域名和真实浏览器认证器上验收。

日期：2026-09-09。本地结果，不代表生产或高可用证明。

- Java 25.0.4.1 / Spring Boot 4.1.1：Maven `clean verify` 通过，共 255 项测试，失败 0、错误 0（88 项外部依赖测试按环境跳过）。
- Next.js 16.3.4：生产 build、TypeScript 检查通过。
- 新旧框架依赖与模块边界：2 项 Node 测试通过。
- PostgreSQL 18.6：独立 lumira_next 库，Flyway V1 初始化完成。
- 真实 HTTP 联调：匿名拒绝、CSRF 拒绝、错误密码拒绝、正确登录、当前账号、模块列表、用户摘要字段检查、权限撤销/恢复、退出后旧 cookie 重放拒绝全部通过。
- 后端健康 UP；新前端 8010 登录页 HTTP 200。
- 浏览器：查看原页和新页截图；新页沿用原始背景，分栏、标题、主色保持同一方向；主题切换按钮状态验证通过。
- 语言持久化、窄屏、已登录后台的浏览器验证已在 2026-09-15 全量浏览器回归中完成；脚本覆盖自动隐藏动效、键盘操作、窄屏抽屉、账号操作、设置、文件、邮件、短信、反馈、敏感词和本地化页面。
- CI 工作流和容器配置已添加，尚未在 GitHub / Docker 执行。

## 未完成范围

生产外部渠道、动态模块安装/升级、对象存储、备份恢复、容量压测、故障切换、多因素验证和全量业务迁移仍未完成验收；公共平台的本地实现与浏览器回归已完成。用户列表已改为服务端分页并通过 105 条跨页回归。

旧系统和 MySQL 未被替换或删除；临时目录不满足最终删除条件。新账号使用独立随机初始化密码，不兼容旧系统账号。
