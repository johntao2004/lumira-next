# Lumira 独立脚手架

状态：第一阶段基础实现；尚未完成业务迁移，不具备已验证的生产高可用容量。

目标边界采用最小不可卸载内核与可插拔业务：内核负责启动、身份认证、插件生命周期及基础存储/公共契约。业务插件卸载必须先检查反向依赖和影响范围，存在依赖者时拒绝卸载，按依赖顺序处理；循环依赖拒绝加载，需要先重构依赖关系。当前宿主仍含待迁出的业务实现，不能把模块开关等同独立插件制品。

容量目标分为 1000 个活跃用户，以及至少 10,000 请求/秒的混合读写与突发刷新；这些是待验证目标，不是当前容量成绩。验收必须分别统计请求到达、成功完成、过载拒绝、错误、P95/P99、积压与资源使用。云原生部署需要验证应用节点弹性、数据库总连接预算、共享数据及节点故障恢复；扩大 CPU、内存或副本数本身不构成达标证据。

数据库初始化：全局数据库仅保留 `db/migration/B52__platform_initialization.sql`，直接创建完整结构、约束、索引、视图、触发器和稳定种子。Flyway 只加载这一份初始化 SQL；历史增量及归档已清理，不支持旧版本数据库升级。测试从同一份初始化 SQL 获取当前结构，不依赖历史升级脚本；插件仍管理各自安装迁移。直接导入、备份与显式重建方式见 [数据库初始化说明](database-initialization.md)。

## 边界

- apps/web：Umi Max 路由、Mako 构建、原生 Ant Design、主题与认证交互；静态前端只访问 `/api`，不直接访问数据库。
- AI 对话历史按当前登录账号保存在同源浏览器的 `localStorage`，包含会话标题、消息、草稿、模型选择和当前会话；切换、路由返回和刷新可恢复，不跨浏览器或设备同步。存储读取/写入失败时显示提示；不持久化用户查询结果快照、管理员密码或操作确认表单，历史消息中的待确认步骤在恢复时失效。模型请求使用 `/api/platform/ai/chat`，只传当前会话最近 20 条消息及可选的附件文件标识；模型选择与上传工具整合在 Sender 输入框中。附件读取、限制和适配契约见 [AI 聊天输入与附件](ai-chat.md)。
- backend：Java 25 / Spring Boot 4.1.1；identity 负责身份；modules 负责模块契约；web 是 HTTP 适配。
- 资料字段默认包含显示名称、真实姓名、出生日期、地区及头像图像；旧配置读取时补充缺失的头像，沿用已有基本资料分组名称及配置版本，不在读取时写库，已配置头像的显示/必填等规则保留。达到 100 个字段的旧配置保留原内容。资料字段编辑采用顺序列表与当前字段设置，拖动、键盘及上移/下移均同步更新 `sortNo`，保存后前后台按该顺序读取；自定义字段标识自动生成后可编辑，内置标识与类型受保护。
- PostgreSQL lumira_next：独立账号、权限、数据库会话；不连接旧 MySQL。
- 模块以各自配置贡献的 PlatformModule Bean 注册，内核只负责组装；启动时拒绝重复标识、缺失依赖、循环依赖和未声明模块归属的 Controller。前后端边界检查见 [模块化架构](modular-architecture.md)。
- 导航以基线中的版本化菜单表为服务端事实源；运行时按权限、全局范围和已启用模块生成树，树形、侧栏和面包屑共享动态标签与层级。前端模块清单继续负责静态页面组件和权限元数据，权限不足但保留可见子项的 PAGE 祖先只参与面包屑，不作为可路由页面。PAGE 标识和路径有唯一约束，受限 componentKey 只连接前端代码注册表；未知组件使用受控占位页，绝不执行菜单数据中的 HTML 或代码。
- 敏感词策略作为可停用公共模块接入：词条采用软删除和乐观版本，匹配使用不可变 Aho-Corasick 快照。所有角色共用词库，角色仅配置管控开关；公共提交边界统一检查业务 JSON 文本和上传文件名，命中元数据单独保存，阻断先于业务写入。读取、写入、导入继续使用独立全局权限，详见 [全局敏感词管控](content-policy.md)。
- 运行时本地化作为可停用公共模块接入：文案键采用软删除和乐观版本，翻译按受控语言集合存储为 JSONB；管理 API 支持 CRUD、批量导入、启停和发布版本，公开 bundle 通过短 TTL 缓存读取，前端静态词典保持故障回退。读取、写入、导入分别使用独立全局权限，变更和发布写入审计。
- 短信与账号手机验证作为平台能力接入：SMS webhook 的非敏感配置使用版本化 SettingStore，供应商凭据使用加密 SecretStore；发送请求先写入加密 `platform_sms_delivery` 队列，再由带租约令牌和虚拟线程的 worker 发送，网络结果按 ACCEPTED、AUTHENTICATION_FAILED、CONNECTION_FAILED、DELIVERY_UNCERTAIN 分类。`platform_account_phone` 保存当前账号可见的手机号验证状态和验证码摘要，绑定要求当前密码、E.164 规范化、冷却、日上限、失败次数和乐观版本；短信未配置时返回 503，不创建验证码记录。
- 短信队列提供全局只读运维查询：只返回任务 ID、状态、尝试次数、结果码和时间字段，按 `platform:audit:read` 与全局范围保护；查询不会解密正文、读取租约 token 或暴露手机号。分页、状态筛选和任务 ID 搜索沿用邮件投递的 ResourceTable 契约。
- 安全策略配置使用强类型 `SecuritySettings` 和版本化 SettingStore：密码规则按 Unicode 码点及 256 UTF-8 字节上限校验，登录/找回及邮箱/手机验证码限流与挑战存储共享账号/来源/TTL/配额策略；密码新写入使用版本化 Argon2id，旧 BCrypt 仅兼容读取并在成功认证后升级；热路径采用 2 秒有界缓存，保存使用版本冲突校验并写审计；安全策略不提供配置历史、恢复与手动重新载入。会话 Cookie 生命周期、CAPTCHA/MFA 供应商不伪造为不可生效的配置。
- 审计流由 `AuditRetention` 按配置保留期执行有界批量清理，单批使用 `FOR UPDATE SKIP LOCKED`，不会长时间锁住整张审计表；登录限流与超长密码拒绝在账号已识别时写入账号安全事件，未知账号仍不落库。
- 前端隐藏菜单只是体验优化，后端对每个接口独立鉴权，未声明的接口默认拒绝。
- Outbox 事件由模块显式注册消费器，worker 使用 PostgreSQL 租约和虚拟线程并发执行，成功确认受租约令牌保护；保留清理只删除已投递记录，死信保持可审计。运维人员以全局 `platform:events:replay` 权限和必填原因人工重放死信，审批历史与状态重置同事务写入。
- 会话使用 Spring Session JDBC，密码使用 DelegatingPasswordEncoder/Argon2id，登录与退出启用 CSRF，cookie HttpOnly/SameSite=Lax；权限每次请求从数据库重新读取，已撤销的授权不依赖旧会话快照。生产必须设置 LUMIRA_COOKIE_SECURE=true 并提供 HTTPS。
- 当前尚未启用公开注册；公开注册、联系方式验证及默认普通角色的拟议契约见 [公开注册设计](public-registration.md)。统一角色与认证策略见 [角色认证设计](role-authentication.md)，P04/P06–P10 的依赖、工作包和验收分工见 [认证实施计划](role-authentication-plan.md)；均仍需实施与独立验收。不填充虚构赛事，不将旧系统 iframe 嵌入新应用。

## 新接口

GET /api/auth/csrf：返回 CSRF headerName/token，建立数据库会话。
POST /api/auth/login：URL-encoded username/password + CSRF；成功旋转会话。
GET /api/auth/me：当前账号与权限。
POST /api/auth/logout：带 CSRF，销毁会话。
GET /api/platform/modules：platform:modules:read 权限，返回注册模块。
GET /api/platform/event-outbox：platform:audit:read 全局权限，返回脱敏事件元数据；POST `/api/platform/event-outbox/{id}/replay` 需要全局 `platform:events:replay`、CSRF 和必填原因，只能重放死信并保存审批历史。
GET /api/platform/users：platform:users:read 权限，返回 PageResult（items/total/page/pageSize）；支持 page、pageSize（1–100）、search，服务端分页和名称/账号搜索。
GET /api/account/phone：返回当前账号手机号验证状态；POST `/api/account/phone/request` 请求验证码，POST `/api/account/phone/confirm` 消费一次性验证码，均要求登录、CSRF 和当前密码。
GET/PUT `/api/platform/settings/sms`：读取或版本化保存短信 webhook 配置；凭据只接受 KEEP、REPLACE、CLEAR 操作，并受全局设置权限、审计和加密密钥约束。`/history` 与 `/rollback` 沿用其他设置的版本边界。
GET/PUT `/api/platform/settings/security`：读取或版本化保存密码、登录/找回限流和验证码 TTL/配额策略；`/history`、`/history/{version}` 与 `/rollback` 沿用统一设置版本、审计和全局权限边界。

审计保留由 `lumira.audit.retention`、`lumira.audit.retention-batch`、`lumira.audit.retention-interval-ms` 和 `lumira.audit.retention-initial-delay-ms` 配置；保留期限制为 7 天至 10 年，批次限制为 1–5000。

## 迁移次序与退出条件

1. 基础：独立构建、启动、登录/退出、权限与模块机制、原生布局。
2. 平台：组织/角色/数据范围、动态菜单、品牌配置、审计、文件、通知、任务、Outbox、监控与部署。
3. 业务：逐个迁移赛事、报名、支付、评审；校验历史数据、接口和页面行为。
4. 门户：由赛事公开接口驱动列表/详情，发布后更新缓存，提交时实时校验规则。
5. 切换：全量功能矩阵、数据数量/关联校验、压测及恢复演练通过后，将新目录内容提升到正式位置。
6. 清理：保留 Git 历史与迁移备份；新入口验证通过、旧引用归零后才删除临时空目录，不删除未迁移代码或数据库。

第一阶段不暴露未实现的注册和第三方登录按钮；密码找回已接入登录抽屉并受当前安全策略约束。基础外观沿用旧版的背景资产、分栏结构、主色、字体与圆角，尚不等于全站视觉等价。

## 独立插件

工单反馈、本地化和敏感词实现位于根目录 `plugins/`，宿主通过稳定 SDK、PF4J 独立类加载器和 Spring 子容器管理其生命周期。前端页面按当前运行制品动态注册。安装与卸载进行完整依赖预检、权限与版本核验，保留业务数据和独立迁移记录；专用模块历史已撤销，操作写入统一审计。详见 [插件开发与运行](plugins.md) 与 [模块生命周期](module-lifecycle.md)。

## 密码连续字符规则

安全策略的 `passwordAllowConsecutiveCharacters` 同时控制相邻重复字符与连续序列。关闭时，新建账号、修改密码及找回密码统一拒绝相邻重复字符（如 `aa`、`11`），以及至少三个相邻递增或递减的同类字母/数字（如 `abc`、`cba`、`123`、`321`）。连续字母忽略大小写，按 Unicode 码点处理；分隔后的 `a-b-c`、仅两个连续字母、符号或 emoji 序列不属于连续字母/数字。默认值与现有配置不变；现有密码登录不执行新密码规则，不强制修改已保存密码。
