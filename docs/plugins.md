# 插件开发与运行

## 目录与构建

根目录 `plugins/sdk/` 是稳定契约；`plugins/feedback/`、`plugins/localization/`、`plugins/sensitive-words/`、`plugins/mfa/`、`plugins/diffuse-avatar/`、`plugins/payment-core/`、`plugins/wechat-pay/`、`plugins/alipay-pay/` 各自包含 README、plugin.json、backend、frontend、migrations 和构建生成的 dist。每个 README 明确其插件用途、依赖、构建和安装方法。MFA 的认证状态与接口见 [二次认证说明](mfa.md)；扫码支付的模块边界、REST API、安全和运营限制见 [支付插件说明](payments.md)。

使用 Node 24、pnpm 10.33.0、JDK 25：

```sh
pnpm plugins:typecheck
pnpm plugins:build
# 另行构建从未进入宿主构建的示例
pnpm plugins:build sdk/examples/hotplug-probe
```

构建命令先编译 SDK/宿主契约，再 javac 编译插件、生成 PF4J JAR、编译共享 React/Ant Design 的页面入口、复制迁移并生成 SHA-256 文件清单。构建 ZIP/JAR 固定时间戳，以便相同源码重复构建产生相同包。编译产物被 Git 忽略。

私有 Java 依赖放 `backend/lib/*.jar`；构建时加入编译类路径并复制到包。前端私有库由 esbuild 打包，宿主模块清单见 `plugins/sdk/frontend-modules.json`。存在于 `plugins/<dependency-id>/plugin.json` 的插件依赖会先构建并递归放进 `dependencies/*.lumira-plugin.zip`；核心依赖无需打包。离线依赖版本必须精确匹配清单。

宿主框架和 SDK 由平台提供，安装包不能重复覆盖。插件通过 common 与各模块 api 契约调用平台，禁止引用其他插件实现。SDK v1 包括认证、全局范围、权限、JdbcClient/事务、审计、文件、通知、字典、AI 文本生成、支付渠道 SPI 和可选扩展调用。安装清单格式 v2 要求每个授权页面使用本插件独有的访问权限，并通过 `route.operations` 显式映射该页操作；页面权限和操作权限不能跨页复用。格式 v1 继续读取历史清单，但不会推测或补齐它没有声明的操作映射。前后端 SDK 应按兼容契约演进。

## 安装包与接口

内核适配器通过 `PluginServicesPort.service(owner, contract, call)` 同步调用指定插件的共享服务。调用按当前运行实例取得租约，事务内调用持有租约直到提交或回滚；它与声明依赖的注入代理使用同一执行路径。明确声明的依赖代理优先于父容器兼容适配器，不接管提供方 Bean 的销毁责任。

业务配置中的动态引用通过 `PluginDependenciesPort` 阻止提供方停用或卸载。检查必须读取当前配置；卸载预检和排空调用后再次检查，引用变化使旧计划失效。个人资料字段仍引用字典时，需要先手动解除字段引用。计划版本绑定持久化引用检查结果，不绑定本次操作临时的忙碌状态。



导航管理由 `navigation-console` 插件提供，读写菜单的管理接口和界面随插件装卸。内核保留只读运行菜单接口、共享菜单值类型和树投影，不依赖管理插件或其 JDBC 仓库；管理插件卸载期间已有菜单仍按权限和模块状态过滤。模块引用通过只读 `ModuleCatalogPort` 校验，注册但未运行的模块可保留引用。插件保数据卸载不删除菜单或覆盖其父子关系、文案、排序及版本。



首次初始化的默认业务包由 `plugins/bootstrap.json` 显式列出；空数组表示只启动内核、不默认安装业务包。固定镜像打包同一份首装清单，并校验选中的插件都属于批准制品图，不在内核中硬编码业务名称。已初始化数据库仍以持久化安装记录为准，不会因为默认清单变化而自动安装、卸载或启用业务包；恢复已有安装不要求旧镜像补写首装清单。通知与运行监控分别由 `notifications`、`monitoring` 包提供，卸载时保留 B52 管理的通知及监控源数据。反馈依赖通知；卸载通知之前必须显式解除或卸载依赖方。

AI 工作台由 `ai` 包提供，目录操作依赖 `directory`，本地化 1.0.13 的翻译能力显式依赖 AI。模型、分享、待确认操作、设置和加密凭据保留在原有表中；公开分享的兼容网址使用宿主通用只读视图，内容服务随 AI 插件装卸。待确认操作清理任务由插件资源登记、排空和撤销。

插件间直接注入的服务契约须继承共享 `PluginServicePort`，且提供方必须是清单声明的直接依赖。子容器注入的是运行时代理，每次调用解析当前提供方并取得租约，不保留旧版本 Bean；事务内调用持有租约直到提交或回滚。依赖代理不会作为当前插件自己的扩展再次发布。插件私有类加载器中的接口不能冒充共享契约，多个依赖提供同一注入契约时拒绝加载。

安装包包含 `plugin.json`、`backend/plugin.jar`、`frontend/plugin.js`、可选 CSS、`migrations/*.sql`、私有库和离线插件依赖。清单声明精确版本、入口、权限、HTTP 方法/路径、页面/导航及每个文件摘要。规则可用 `authenticatedOnly` 声明“必须是非匿名的登录账号”，供账号自助接口使用；它不能与公开访问、全局授权或权限列表组合。宿主支持 SDK 1 和 SDK 2；使用逐权限范围 `authorityScopes` 的包必须声明 `sdkVersion: "2"`，旧宿主据此拒绝不支持的包。未使用新字段的历史包继续使用 SDK 1；平台版本要求通过 platform 核心依赖声明。

模块管理 API 前缀：`/api/platform/modules/plugins`。

| 方法 | 地址 | 用途 |
| --- | --- | --- |
| GET | `/` | 安装状态和制品清单 |
| POST | `/import` | 导入原始 application/octet-stream ZIP，最多 64 MiB |
| POST | `/{id}/install-plan`、`/{id}/uninstall-plan` | 预检依赖、冲突和状态 revision |
| POST | `/{id}/install`、`/{id}/uninstall` | 使用 `{revision}` 执行生命周期 |
| GET | `/operations/{uuid}` | 任务状态、当前步骤和失败原因 |
| GET | `/frontend` | 当前可用前端插件 |
| GET | `/assets/{digest}/{file}` | 当前已发布的前端文件 |

管理操作执行认证、CSRF、模块权限和全局范围检查。动态 Controller 必须声明自己的 `@ModuleEndpoint`，HTTP 方法及授权规则必须在 manifest 登记；实际业务服务继续执行自己的数据范围检查。公开接口只允许显式登记的 GET。唯一例外是供支付机构服务器通知使用的 `signedCallback: true`：它必须是 POST、精确的字面路径 `/api/plugins/{本插件ID}/callbacks/{小写短横线标识}`，且只能在该插件已启用时绕过 CSRF 和登录授权；卸载或停用后立即失效。普通公开 GET 规则不变，也不得使用通配符、路径模板或登记其他插件/平台路径。页面权限与模块状态共同控制导航，动态页面不授予任何权限。

`permissions` 是插件实际拥有的 authority 清单，代码使用 `platform:{plugin-id}:{page-or-action}`，插件不能声明平台或其他插件的权限。可选的 `permissionGroups` 只提供目录展示信息，格式为 `{id,title,permissions:[{code,label,requires?}]}`；各分组的权限合计必须覆盖 manifest 所有权限且每项只出现一次。`requires` 可标示执行操作需要的宿主或已声明插件 authority，但不会代替服务端检查。旧 manifest 未提供分组时，宿主会把字符串权限放入一个以插件名称命名的兼容分组。目录 API 会把组 id 规范为 `{plugin-id}:{group-id}`，避免不同插件复用本地组名时冲突。分组和标签按普通文本显示，不会授予额外 authority。插件 id 不能占用平台权限资源或平台模块 id。

新版 route 显式使用 `{id,label,permission,operations?,scopedOperations?,group,order,global}`：`permission` 是本页独有的访问权限，`operations` 把页面内稳定动作 ID 映射到本插件声明的独立权限代码，例如 `{"permission":"platform:wechat-pay:orders-access","operations":{"read":"platform:wechat-pay:orders-read","refund":"platform:wechat-pay:orders-refund"}}`。页面访问和每个动作分别勾选、分别保存；调用动作必须同时持有该页权限和对应操作权限。`global` 声明页面访问范围；`scopedOperations` 列出使用目标范围授权的动作，未列出的动作默认为全局。动作只能映射到一个页面，所有映射代码必须在同一权限组中声明。旧 manifest 可省略 `operations`，此时目录明确显示没有操作映射；宿主不会从权限名称、`read` 前缀或资源路径猜测页面或动作。依赖插件的历史路由权限仍按兼容规则读取，但不能用于新页面动作映射。

API 规则保留 `authorities` 的旧任一项匹配行为，不能改解释为 AND。格式 v2 的权限规则必须把本页访问权限放入 `allAuthorities`，并至少要求该页一个已映射操作；`authorities` 只能列该页操作，不能替代页面访问门槛。新增的 `allAuthorities` 表示额外的全部匹配条件，必须属于插件已声明的页面访问/操作映射；跨页面组合规则必须把所用每个操作对应的页面访问权限都列入 AND 条件；同时出现两个字段时，旧列表先按任一项匹配，再要求 `allAuthorities` 全部满足。旧格式 v1 和旧 `global:true` 规则继续保持原有匹配及全局范围语义。混合范围路由可额外声明 `globalAuthorities`：其中至少一项必须同时满足普通权限匹配并具有全局范围；该字段是任一项匹配，适用于“页面访问可按本人范围、管理动作必须全局”等组合。它不能放宽 `allAuthorities` 的持有要求。SDK 2 的 `authorityScopes` 按权限代码显式声明 `global` 或 `scoped`，只能引用本规则已要求的权限，不新增授权条件。例如用户打印同时要求用户页的目标范围访问、模板页的全局访问和打印动作；任一条件缺失即拒绝。没有映射的权限继续采用规则 `global` 的默认范围，旧 `globalAuthorities` 保持任一项全局匹配语义。实际服务也必须校验页面访问和动作，并保留已有用户/角色数据范围；manifest 规则不能替代服务的对象级检查。`authenticatedOnly`、公开读取和签名回调规则保持既有语义，不能与权限规则混用。

`GET /api/platform/permission-catalog` 返回平台与当前已安装插件的动态权限组，页面结构为 `{id,path,title,navigationGroup,navigationOrder,accessPermission,operationPermissions,accessScope,operationScopes}`；权限项结构为 `{code,label,requires,editable,retired}`，插件组的 `source` 为 `plugin`。插件页面只从该 route 的显式映射生成，旧清单缺少映射时 `operationPermissions` 为空，不能按组内其他权限补齐。插件权限只在当前已安装 manifest 声明时可新增到角色。首次给角色授予插件权限，仍须通过角色创建或更新的 `platform:roles:create` / `platform:roles:update` 检查，并由持有全局 `platform:modules:install` 的操作者为当前已安装插件提供的权限执行受审计引导；此引导不能授予平台权限、未知权限或已卸载插件权限。迁出的目录插件继续拥有 users、online-users、roles、organizations、print-templates 的有限历史权限别名；这些权限仍执行原有全局委派约束，不因插件安装权限而获得引导授权。卸载后，角色中保留的历史权限会显示为退役条目，只能保留或移除，不能再次新增。

运行时每次刷新都按插件 ID 替换完整目录快照；安装后注册，卸载后移除，避免静态权限集合累积。卸载不会删除角色已保存的 authority；目录会将不再由已安装插件声明的历史授权列为退役权限，供查看现有角色使用。角色可以保留或移除退役权限，但不能新增。插件服务卸载后不再注册路由或执行能力；重新安装后再按当前 manifest 注册权限和页面。

插件生命周期权限按操作拆分：导入包和首次安装需要 `platform:modules:install`，启用已安装插件需要 `platform:modules:update`，卸载需要 `platform:modules:uninstall`。安装操作若同时启用已安装依赖，也要求 update。后台任务执行时会再次检查对应权限。业务 API 的细粒度权限仍由插件 manifest 和服务层共同执行；服务层原有的数据范围、全局范围与事务边界必须保留，不能因拆分 authority 而放宽。

## 后端资源所有权

PF4J 为每个插件创建独立类加载器，框架与 SDK 从宿主加载。`PluginEntry.configuration()` 提供独立 Spring 子容器配置，容器可以使用宿主稳定 Bean。健康检查通过才注册 HTTP 映射并发布能力。

后台任务使用子容器中的 `PluginResources.schedule`，订阅/其他可关闭资源用 `own`。每次托管任务、HTTP 请求和 `PluginExtensionsPort` 扩展调用取得租约，卸载先停止接收并等待租约排空（最多 30 秒）。Spring 容器负责销毁本地 Bean，PF4J 停止/卸载并释放类加载器。禁止自行启用 `@EnableScheduling`、长期持有插件引用或启动未登记线程。

插件执行上下文绑定插件 ID、制品摘要、模块状态版本和节点身份，并传递给宿主提供的数据源。插件须通过该数据源访问平台业务数据；同一平台集群的节点连接同一主库。显式事务在同一物理连接上持有数据库围栏，直到提交或回滚；自动提交语句也在受围栏保护的短事务中执行。安装、停用和卸载提交需要取得受影响插件的排他围栏，节点心跳失效不能代替事务排空。只读判断使用数据库实际的 `transaction_read_only`，不依赖可能被驱动忽略的只读提示。事务边界和保存点须通过 JDBC/Spring API 管理；SQL 文本可以包含多条业务语句，但不能自行提交、回滚或建立保存点。原生 PostgreSQL 连接接口须在显式事务中使用。

数据库围栏是集群协调的前置条件。默认配置（`LUMIRA_PLUGINS_CLUSTER_ENABLED=false`）仍保持多健康节点在线变更限制；仅在共享不可变制品目录（`LUMIRA_PLUGINS_ARTIFACTS_PATH`）、安装并启用可选基础设施插件 `cluster-runtime`（`1.3.0`，`V4__plugin_initialization.sql`，声明 `protocol_version = 2`）且显式开启 `LUMIRA_PLUGINS_CLUSTER_ENABLED=true` 时，宿主才启用多节点两阶段在线装卸与启停协调。

`cluster-runtime` 独立管理协调日志、参与节点与协议准入表，不在默认业务初始化清单中自动启用。宿主的 `ClusterOperationJournal` 与 `ClusterRuntimeCoordinator` 在宿主数据源读已提交事务中记录原操作者账号 UUID、目标制品摘要、目标图版本及冻结的参与节点集合，并通过 `execution_fenced` / `prepared_fenced` 实施执行期围栏：准备阶段验证并排空受影响插件后确认原图，领导者收齐确认后在排他生命周期围栏内提交权威图元数据并推进至 `COMMITTED`，随后各参与节点加载或卸载本地运行时并确认目标图，直至进入 `COMPLETED`；任一节点否决或超时则转入 `ABORTING` 并在全员清理确认后收束至 `ABORTED`，租约过期后可由存活节点递增所有权代次接管。未完成协调期间普通装卸与模块开关返回 `pluginClusterOperationActive`，且禁止卸载 `cluster-runtime` 或通过删表/删日志解锁。

协议准入通过向前迁移独立维护（V1→V4 保留全部已发布迁移记录与校验和）。激活事务锁定准入记录，核验全部存活节点的 `protocol_version = 2` 声明、就绪状态与已加载图版本后冻结参与集合，并发注册与心跳等待同一准入锁。冷替换或重启的新节点在就绪前执行 `recoverLocalNodeIfNeeded`：提交前故障按中止路径排空并否决未决操作，提交后故障先收敛至权威已批准插件图再确认完成。在线 `POST /api/platform/modules/plugins/import` 仍要求单活跃节点（`activeNodeCount() <= 1`）或事先通过共享 `LUMIRA_PLUGINS_ARTIFACTS_PATH` 分发制品。

固定清单启动将制品目录、安装状态、模块版本、导航接管和图批准放在同一元数据事务中，插件加载及健康检查失败会回滚这些元数据；提交成功后才发布节点就绪。镜像移除模块也按数据库中变更前的依赖关系和配置引用检查，即使依赖双方同时从新镜像移除，仍须手动按顺序解除依赖。提交前在既有管理恢复屏障内重新检查配置引用，使用读已提交隔离级别读取已经完成的写入。

插件自身的初始化与向前迁移仍由独立历史管理，不通过回滚、改写历史或 repair 撤销已执行的 SQL。元数据发布失败后可重试正确的目标制品；如果插件已执行旧代码不能识别的向前迁移，不能据此声称旧镜像一定可回退。

初始化迁移器从数据源的当前 schema 读取历史，并显式将同一 schema 交给 Flyway；默认 `public` 的行为保持一致。不能从一个 schema 读取历史，却让 Flyway 向另一个 schema 写入历史。

普通读已提交事务先取得共享事务围栏，再以新的语句快照核对插件元数据，不在公共元数据行上追加行锁或 WAL。可写的可重复读和串行化事务仍锁定元数据行，以拒绝旧快照；隔离级别和只读状态均取数据库实际值。固定图发布对变更的插件使用相同的排他围栏，旧节点心跳过期也不能越过未结束的事务。相同批准清单的扩容启动只核对现状，不重新写入安装记录、模块开关或批准时间；已有卸载状态保持卸载。

账号头像扩展使用稳定的 `common.GeneratedAvatarPort` 契约：启用的插件可以提供 `generate(UUID accountId)`，返回 PNG 字节；宿主在持有插件调用租约期间使用结果，不缓存插件 Bean。`diffuse-avatar` 依赖 `identity@1.0.0` 和 `files@1.0.0`，生成确定性的 256 × 256 弥散渐变头像，不提供业务路由或权限。

支付扩展由 SDK `common.PaymentGatewayPort` 提供。`payment-core` 通过 `PluginExtensionsPort` 租约调用当前已启用的渠道实现，不持有实现 Bean；`wechat-pay` 与 `alipay-pay` 只依赖该 SDK 接口和支付核心插件，不依赖彼此的源码。

Flyway 使用每插件 `plugin_<id>_schema_history` 独立记录，仅执行向前迁移；现有表通过接管迁移处理。迁移必须适合事务执行。卸载不删表、不删除发布包，也不删除源码。管理员默认选择保留数据，也可显式选择删除所选插件的业务记录。

宿主 B52 初始化直接提供独立页面/操作权限目录和 `platform_normalize_legacy_permission_projections()`。插件的权限最终化迁移依赖这个函数，缺失时失败关闭；已发布插件脚本中保留的旧宿主版本标识只是历史文本，实际前置条件是 B52 提供的函数存在，不需要旧全局迁移文件。部署时先完成宿主单文件初始化，再安装插件。本次全局迁移清理不改变各插件独立的迁移历史或安装协议。

在 B52 宿主上全新安装反馈、本地化或敏感词插件时，如果插件迁移历史为空，宿主会用 Flyway Community 的自定义 no-op resolver 将待执行的旧权限专用 `V2` 记入历史：原版本、脚本和校验和保持一致，旧 SQL 不运行。若插件自身已有先前的版本迁移记录，则仍按该插件的安装协议处理待执行版本；随后追加的 `V3` 只处理已废弃且不与当前独立权限同名的聚合代码，并调用宿主权限归一函数。MFA 的 `V6` 含管理员引导授权，在当前宿主上待执行时会以原始校验和记录为已执行但不运行；`V7` 只转换可识别的旧 MFA 来源，不自动授予新的管理页或操作权限。这样全新安装保留单独授予的页面/操作权限，也不会重放 MFA 引导授权。当前最终化包版本为 feedback/localization `1.0.11`、sensitive-words `1.0.8`、mfa `1.0.9`、payment-core/wechat-pay/alipay-pay `1.0.5`。

MFA `1.0.9` 的 `V7` 可处理插件自身已有的旧 `mfa:read`/`mfa:manage` 关联。旧 `mfa:reset` 只在权限映射元数据要求的全局重置来源与目标范围 `users:update` 来源都存在时转换为新重置操作；这两项可以来自直接授权和角色组合，角色来源保留原始数据范围。当前宿主上的新安装不重放旧 V6 引导授权，新页面和操作权限由角色管理员显式配置。

## 前端与扩展

`FrontendModule` 导出页面注册，动态脚本使用共享 SDK。安装后加入页面与导航；卸载后撤销注册、卸载 React 组件、删除脚本/CSS、清理所属查询缓存并刷新身份/导航。本地化卸载后恢复内置文案；敏感词卸载后动态内容策略提供者撤下，再安装恢复原词库和角色策略。

前端插件必须在 effect 清理中撤销自己的事件、订阅和定时器。插件私有资源不能绕过 SDK 全局登记。动态加载不需要 Umi 重构建或平台进程重启；页面刷新会按当前安装状态重新加载。

## 运维与验证

首次部署插件运行时前按项目备份流程保存数据库。先运行 `pnpm plugins:build`，确保需启用的插件包已构建，再启动平台。生产应保存持久的 `.runtime/plugins/` 制品、数据库与对应宿主 SDK；不能只复制插件源码替代运行制品。

### 本地启动与换库插件清单

本地服务只读取 `.runtime/local.json` 这一份 canonical 配置；历史配置文件不会被自动发现或合并。`LUMIRA_DB_URL` 必须明确指向 loopback 上的 `lumira_*` 数据库，`LUMIRA_PLUGINS_PATH` 指向该服务自己的插件运行目录。数据库地址、角色和密码保存在本机运行时配置中，不硬编码进仓库；密码只通过 `PGPASSWORD` 环境传给 PostgreSQL 客户端，不放入命令行参数。

首次接管由 `node scripts/local-runtime.mjs adopt --pid <PID>` 捕获固定清单 `.runtime/local-plugin-inventory.json`；有意完成插件安装、版本升级或启停后，先检查预期状态，再显式运行 `pnpm plugin-inventory:capture`。捕获会只读查询 B52 数据库，记录数据库身份和每个制品的 ID、版本、归档摘要、manifest 摘要、安装状态、显式安装状态和模块启用状态；同时校验运行目录中的制品目录、数据库与磁盘 manifest、JAR 及 manifest 中登记的每个文件摘要。清单缺失、表缺失、空制品清单、数据库不可达或任何制品校验失败都会拒绝捕获。若现有清单发生变化，捕获还需要明确理由，例如 `node scripts/local-runtime.mjs capture --accept-changes "批准升级头像插件"`，并会先备份旧清单；数据库 endpoint、名称、OID 或角色变化不能用 capture 重新认领，必须切库。

本地启动在启动 Java 前和服务 readiness 后都会调用同一固定清单的校验。普通校验不会写库或自动补装插件；数据库身份、插件版本/摘要、installed、explicitInstall 或 enabled 与清单不一致时都会失败关闭。确认某项变化确为预期后，带非空原因重新执行显式 capture，不能靠删除清单绕过检查。

换库前先停止服务并确保源、目标数据库没有其他客户端连接。默认连接检查复用应用角色，并要求该角色拥有 `pg_read_all_stats` 可见性；若源、目标使用同一个本地 PostgreSQL Docker 容器，可显式传入 `--activity-audit-docker-admin true`，由容器的 `POSTGRES_USER` 仅执行只读连接活动审计，插件清单及数据库身份仍由 canonical app role 查询，应用角色权限和 inventory identity 不变。该参数只接受小写 `true`；无法确认所有角色的连接状态时会拒绝切换。用 `node scripts/local-runtime.mjs switch --target-config <目标配置>`（可选 `--activity-audit-docker-admin true`）验证当前源库仍符合 pin、目标是不同数据库、源与目标插件清单逐项相同且两侧运行制品完整；流程会串行化启动、配置接管和其他本地 runtime 更新，然后逐个原子写入 canonical 配置、pin 和固定清单，写后复核。发生异常时会逐个尝试恢复所有原文件并报告恢复失败项。多文件更新不具备断电原子性；中断遗留的 `.runtime/local-runtime.lock` 会阻止后续受锁操作，须先检查配置、pin、清单与备份并确认没有进程仍在更新，再移除锁。目标清单缺少插件（包括 `diffuse-avatar`）、版本、摘要、安装状态或启停状态不一致时会列出差异并拒绝切换；换库护栏只读验证，不导入、安装或修改插件数据。

找不到原生 `psql` 时，可在 canonical 本机配置中提供 `PSQL_BIN` 或 `LUMIRA_PSQL_DOCKER_CONTAINER` 指定客户端；仓库不固定本机容器名。不得将 BookKin 或其他业务库作为目标。

升级流程为预检卸载旧版本、导入新版本、预检安装。修改已发布插件时提高 patch 版本，不要用相同版本号导入不同摘要；当前运行时通过卸载、导入、安装新包升级，没有原位替换已安装制品的路径。默认 `PRESERVE` 会保留业务表和已保存角色权限，插件权限目录则在卸载时撤下、安装新包后按新 manifest 重建。向前迁移为新细粒度 authority 添加授权；已发布迁移不可改写。旧权限若要求多个来源共同成立，迁移应把每个旧授权来源分别保存为唯一的 `platform:__v50:<slug>` 隐藏 authority，并在 `platform_permission_projection_component` 注册逻辑权限所需的组合及全局/目标范围；不能将旧组合直接复制成宽泛的新操作。逻辑权限之后的显式新授权保持独立，只有废弃的旧代码才进入退役列表。MFA 升级前要确认可承受短暂卸载期间服务不可用，并按插件说明保留恢复状态；不要为了升级手工删除 MFA 状态或数据库权限记录。移除任何被安装消费者依赖的插件会被阻止，即使消费者已停用。卸载计划只包含明确请求移除的插件；即使某个提供者原先随依赖自动安装、目前已无引用，也会保留它的数据和运行状态，必须对其另行预检并显式卸载。失败任务显示具体阶段，迁移与业务数据保留。

### 固定发布图部署

生产镜像启用 `LUMIRA_PLUGINS_FIXED_GRAPH=true`，插件 ZIP 与 `approved-graph.json` 随同服务器镜像只读发布；`LUMIRA_PLUGINS_APPROVED_GRAPH` 指向该文件，`LUMIRA_PLUGINS_PATH` 指向当前容器私有的可写运行目录。仓库 Docker 配置使用 `/app/plugins/approved-graph.json` 与 `/tmp/lumira/plugins`。PF4J 的 `loaded/` 必须由各节点独占，不能挂载为共享运行目录。

制品默认仍放在原有 `<LUMIRA_PLUGINS_PATH>/artifacts`，不迁移或删除已有文件。可选 `LUMIRA_PLUGINS_ARTIFACTS_PATH` 指向各节点共同挂载的制品目录；它只存放按 ZIP 摘要寻址的不可变解包内容，不能包含或位于任一节点的 `loaded/` 内。导入先在同一制品文件系统的临时目录完整校验，再发布摘要目录；已有目录及并发发布的结果必须重新通过文件摘要与类兼容性校验，失败时保留原目录并拒绝复用。共享目录不能由外部程序原地改写。已有安装切换路径前，必须先将对应摘要的完整制品复制并校验到目标目录；缺失或损坏会阻止恢复，不会自动覆盖旧数据。共享挂载的可见性、目录重命名与故障恢复需在目标存储上验证。

共享制品允许新节点从同一目录恢复已有插件图，不能替代集群在线变更的准备、排空、提交和恢复协议。运行时按本节点心跳租约校验请求与生命周期操作，其他节点的健康心跳不能代替本节点；请求内的就绪缓存也受剩余租约期限限制。租约过期后停止接收新请求，成功续期后重新检查插件图。已取得执行租约的请求仍须完成或排空。

批准文件由 `node scripts/plugin-runtime-lock.mjs --bundle <输出目录>` 生成，格式如下；每个包的 SHA-256 必须与锁一致，包清单与 ID/版本也必须匹配：

```json
{
  "formatVersion": 1,
  "plugins": [
    { "id": "feedback", "version": "1.0.4", "digest": "<64 位小写十六进制 SHA-256>" }
  ]
}
```

对应 ZIP 放在批准文件旁的 `<id>/dist/<id>-<version>.lumira-plugin.zip`，原始 `plugin.json` 放在 `<id>/plugin.json`。Docker 构建从当前源码生成包和批准文件，并将其复制进服务器镜像。固定图模式下，HTTP 导入、安装、启用、停用、卸载和普通模块状态变更均被拒绝，必须通过发布更新；管理界面会说明这一限制。非固定图的开发运行时仍需单节点；检测到多个活跃节点时会拒绝在线生命周期变更。

发布包含包图或插件状态变化的新镜像前，先停止并排空旧运行节点，再启动新图。B52 初始化基线已经包含 `platform_plugin_runtime_node` 节点心跳表及插件操作的 owner lease 字段；每个节点登记心跳和已加载图摘要，只有 owner 节点续租自己的任务。新镜像在数据库中发现不同的已就绪旧图时不会覆盖共享插件目录或接受请求，节点健康/业务 API 会保持失败关闭。运行时会将 owner lease 已过期、或无 owner 且超过 5 分钟未更新的排队/运行任务标记为中断；这是当前运行时恢复逻辑。每个应用节点必须使用独立的插件解包根目录；QA 第二节点示例根目录为 `.runtime/role-authentication/node-b/plugins`，只复制同一批准的包与锁文件，不共享另一个节点的可写解包目录。

节点检测依赖节点已运行并登记心跳；升级期间仍运行但未登记到当前数据库的旧版实例不能由该机制探测。因此更换运行图前必须人工确认旧实例已停止，不能把双节点本地 QA 当作生产负载均衡、容器编排、数据库故障切换或持续可用性的证明。生产 Docker/代理/数据库部署仍需在目标环境单独验证。

`pnpm test:module-packages` 执行真实 HTTP/数据库生命周期回归，使用独立 `qa-plugin-*` 制品，不改变业务记录。测试后重启用于清空测试制品的内存目录。示例 hotplug-probe 应在宿主启动后构建、导入，验证此前不存在的 Java 接口与前端页面立即可用且宿主 PID 不变。浏览器验收与 HTTP/构建检查分别执行，截图和测试日志保存在 `.runtime`。

`scripts/mfa-smoke.py` 及其 `--passkey-only`、浏览器夹具选项属于历史 MFA 验收工具。新版首次绑定、恢复票据和再次验证已经改变其前提，运行前必须复核端点和隔离环境；不能将旧脚本通过视为新版完整验收，也不能在主库真实账号上运行凭据变更测试。合成 WebAuthn assertion 与实体认证器、真实投递和多节点验收分别记录。详见 [MFA 说明](mfa.md)。

## 插件事件消费者与排空

插件子容器中的 `DurableEventConsumerPort` Bean 在加载时自动加入平台事件注册表；同一次注册原子发布，重复事件类型会使安装失败并撤销已发布资源。插件卸载时撤销注册，运行中的消费持有该次插件加载的租约，直到数据库消费事务和 Outbox 确认完成。停用或排空期间不接纳新消费，事件按无消费者策略保留待后续处理。消费者不得在数据库事务中产生无法回滚的外部网络副作用。

前端脚本注册绑定实际脚本元素与账号授权 revision。卸载、制品替换、授权切换和最后一个运行时订阅者退出会取消待加载注册，迟到的旧脚本不能完成新的加载；15 秒加载超时释放 pending 状态，后续成功轮询可重新加载。多个页面共享运行时，其中一个页面退出不会撤销其他页面正在使用的插件。已发布页面仍以服务端模块状态和独立授权为准。

`pnpm test:plugin-events` 使用与其他 smoke 相同的显式私有 QA 配置和数据库标记，验证真实插件子容器中的消费者注册、积压处理、消费事务期间卸载、无消费者保留和重装恢复。该脚本会生成隔离夹具，不能使用业务数据库；无消费者事件默认延后五分钟，测试只提前其自身夹具的重试时间。可通过 `LUMIRA_PLUGIN_SMOKE_WORK` 为插件夹具选择独立目录；使用独立 Linux 后端进程时，`LUMIRA_SMOKE_BACKEND_PID` 可将装卸前后身份检查绑定到数据库配置一致的 QA 进程。

Outbox 每个并发槽在队列非空时连续领取任务，轮询间隔仅用于空闲后的唤醒。`lumira.events.concurrency` 默认为 8，允许 1–64；部署时须与数据库连接池、HTTP 请求和其他 worker 的连接预算一起设置，而不是单纯增大线程数。

单节点生命周期操作按受影响插件集合限制接入。运行中的调用仍受实例租约和排空计数保护；受影响插件的新 HTTP、扩展、任务和事件调用暂不可用。其他插件可继续服务，但运行时持续校验未变更部分的数据库指纹、批准记录和心跳；检测到计划外变化仍会停止接入。此机制不解除固定图或多节点的在线变更限制。

插件列表按一次请求批量读取安装记录和模块开关，不为每个插件重复查询整张安装表。批量结果只用于当次响应，不跨请求缓存，下一次请求直接读取新的状态。

HTTP 接入对批准图的数据库校验限定在单次 servlet dispatch 内复用，避免鉴权、扩展和控制器检查重复聚合相同插件图。每次新请求或异步重新分派都重新校验；本节点图 revision 或变更阶段改变时，当次请求也重新校验。关闭/心跳失败始终优先拒绝。已接入的请求沿用其接入快照，插件状态检查及实例 lease 仍独立执行；后台任务、健康检查和生命周期事务不使用此缓存。异常退出同样清除线程作用域。

`pnpm test:plugin-concurrency` 在带 QA 标记的独立数据库上记录有界闭环 HTTP 基线，并在反复装卸期间验证另一个插件的响应。输出包含并发、请求数、吞吐和 P95/P99；它不是生产混合负载、长时间稳定性或故障切换验收。

绑定 `LUMIRA_SMOKE_BACKEND_PID` 时，该测试还在清理夹具后触发 QA JVM 的 GC，检查插件类加载器数量回到基线且两个夹具的类均消失。撤销路由后，运行时会清理 Spring MVC 中属于已排空类加载器的控制器、参数、响应与异常解析缓存，并通过 Jackson 2/3 官方 API 清空转换器类型缓存，释放 Swagger 的类型名缓存。参数转换服务的软引用查询缓存也会清理，已注册转换器保留；不依赖内存压力触发插件回收。API 文档完成后再次清理其类型缓存，覆盖与卸载并发的文档生成。MVC/Swagger 尚无对应公开逐插件释放 API，版本相关字段集中在 `PluginFrameworkCaches`，加载前检查兼容性；升级 Spring/Swagger 必须通过其真实 MVC 回归和 HTTP 回收测试。缓存重建可能带来短暂冷启动开销。

在专用 64 名额 QA 配置中设置 `LUMIRA_ADMISSION_SMOKE=1`，额外验证过载拒绝、恢复，以及 1000 个并发客户端完成总计 10000 次合成探针请求。成功 RPS 和包含拒绝的总响应 RPS 分开报告。这是闭环、公开合成接口测试，不代表 1000 个已登录用户，不是每秒 10000 次混合业务操作；不要在业务环境运行该场景。

HTTP 接入在鉴权和运行时数据库检查之前受 `LUMIRA_HTTP_MAX_IN_FLIGHT` 限制，默认 256，允许 1–4096。超额请求尚未执行，返回 503、`serverBusy` 和 `Retry-After: 1`；客户端不得无限立即重试。异步/流式请求保留名额直至完成，异常退出释放名额。健康探针不占用业务名额。指标 `lumira.http.admission.active` 与 `lumira.http.admission.rejected` 分别反映在途量与拒绝数；过载拒绝不能计入成功吞吐。该上限须与每节点数据库连接、内存和下游预算一起调整。

## 卸载数据选择

卸载预检默认 `dataMode=PRESERVE`。`POST /{id}/uninstall-plan?dataMode=DELETE` 返回当前清理范围和记录数量；执行使用 `{revision,dataMode:"DELETE",confirmation:"插件标识"}`。预检 revision 绑定插件、操作、数据模式及记录数量；执行与请求排空后再次核验，改变模式或数据数量后必须重新预检。删除还须在页面输入插件标识并确认不可恢复提示。任务持久化 data_mode，独立 `plugin.dataDeleted` 审计区分永久清理与普通卸载。

安装包 `dataSets` 按顺序声明 `{table,label,column?,value?}`。宿主只允许插件自己的 `plugin_<id>_*` 表，以及三个接管插件已明确归属的业务表；本地化语言仅清理 `built_in=false`。B52 初始化库不包含旧账号草稿表；当前运行时不读取、写入或清理这类数据，旧全局数据库中的草稿不由宿主基线迁移。所有插件卸载数据集在同一数据库事务中清理，失败全部回滚；已卸载的插件保持卸载，任务显示数据清理失败，可重新安装使用保留的数据。

删除只针对管理员选中的插件，闲置自动依赖始终保留数据。工单清理工单、处理记录和附件关联，共享文件保留；本地化清理自定义文案、覆盖翻译、发布记录与自定义语言，内置语言保留；敏感词清理词库、角色管控和命中记录。表结构、迁移/技术版本记录、权限授权、源码和发布包继续保留，重新安装不会通过旧迁移恢复已删除的业务记录。

插件数据库采用[单文件初始化契约](plugin-initialization.md)：每个内置插件只保留一份最终 SQL，旧记录按原身份和校验和验证，现有数据及历史不重放、不改写。

## 支付渠道扩展

支付渠道通过 `PaymentGatewayPort` 声明自己的插件 ID、凭据字段、配置校验及回调路径。ID 为最多 32 个小写字母、数字和连字符，并以字母开头。`PluginServicePort` 的归属和每插件每服务类型唯一性在发布前验证，其他插件不能冒用渠道 ID。数据库不再枚举具体渠道。

新渠道管理控制器可复用 `PaymentChannelAdminController`，传入 `ProviderPage(id, ORDERS)` 与 `ProviderPage(id, SETTINGS)`；权限按渠道命名空间生成，跨渠道订单请求仍被拒绝。已有 `Page` 枚举、构造器和方法签名保留兼容。渠道自行声明其 `/api/plugins/<id>/callbacks/<名称>` 精确签名回调规则，实现 `callbackPath()`，再经租约访问支付核心的 `acceptCallback`。验签仍由渠道适配器完成，核心校验订单归属、金额、币种和配置版本。微信与支付宝保留原有回调地址。

前端工作台的 `validateQrCode` 可声明本渠道二维码地址规则，仍受统一长度与控制字符限制；聚合订单页面对未声明策略的未知渠道不展示二维码。配置字段、文案和页面由渠道自己的前端制品提供。

支付核心、微信和支付宝 1.1.0 使用精确版本依赖。现有安装需先以 PRESERVE 卸载依赖渠道，再卸载支付核心，导入新制品并安装；不允许改写同版本制品或删除业务数据来升级。已有数据库只执行明确的向前演进，不重放完整初始化。

已卸载旧渠道可暂时保留在目录中，其旧依赖版本不会阻止共享核心升级；重新安装这些旧渠道仍会被精确版本检查拒绝。新导入包及其可达依赖必须立即满足版本要求，已经安装的依赖者仍阻止更换提供方。目录排序共享一次遍历，避免对每个根重复扫描整条依赖链。

`pnpm test:payment-provider` 使用合成渠道验证配置、订单幂等、回调、退款和保数据装卸，不调用真实支付网络。存在原始 1.0.6 发布包时，同时验证旧二进制 SDK 与 V4 保数据升级；缺少旧包会明确报告该兼容场景跳过。

## 文件保留策略管理

`file-console` 独立拥有全部文件与回收站的查询 SQL、预览/下载、版本化删除/恢复/清理业务、默认配额管理和文件中心页面，依赖内核 `files`、`settings`。卸载保留文件、配额与历史；内核继续提供个人文件存储和执行已保存的配额。插件通过 `FileManagementStoragePort` 使用原始存储，不依赖私有文件服务。不同文件的管理写入共享事务保护屏障，权限变更等独占管理操作仍等待在途事务完成。

`file-retention` 独立提供策略编辑、历史和回滚 API 及管理页面。内核存储通过只读策略读取器继续执行已持久化的规则，管理插件卸载不会停掉既定清理策略，也不会删除策略历史或文件。文件中心通过通用、订阅注册状态的嵌入路由显示插件编辑器；卸载立即撤回已挂载的编辑器，重新安装创建新的页面实例。

保存和回滚自动策略最多在当前事务标记 100 个过期文件，其余由周期清理继续推进。这样能限制锁、审计和 WAL 的单事务工作量。后台清理与实际字节删除保持原有版本 CAS、引用保护和失败重试规则。

## 站点与消息渠道管理

`site-console` 拥有品牌上传、站点配置、水印、协议发布和维护管理，以及各自的历史和回滚。内核保留已发布配置的只读接口、品牌资源读取、协议快照和维护登录判定；卸载编辑器不改变已生效的业务规则。维护允许角色的查询和校验属于插件；协议回滚沿用正常发布的事务锁。

`delivery-settings` 拥有 SMTP、短信渠道配置、凭据变更、历史回滚和本地测试操作，依赖 `settings`、`communication`。加密凭据保持原存储上下文，回滚必须明确选择 KEEP、REPLACE 或 CLEAR；配置历史只保存非秘密字段。内核只读配置读取器、原始传输和持久化投递队列不依赖编辑器，管理插件卸载后继续使用已保存的配置。

认证配置弹窗通过通用嵌入路由调用可选编辑器。`FrontendPageContext.embedded` 要求页面单独呈现，并可携带保存完成回调；编辑器的安装、卸载和替换会更新注册生命周期，不能在宿主中静态导入插件页面。编辑器保存后由认证工作流重新读取服务端可用性；登录策略仍需单独保存。
