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

安装包包含 `plugin.json`、`backend/plugin.jar`、`frontend/plugin.js`、可选 CSS、`migrations/*.sql`、私有库和离线插件依赖。清单声明精确版本、入口、权限、HTTP 方法/路径、页面/导航及每个文件摘要。规则可用 `authenticatedOnly` 声明“必须是非匿名的登录账号”，供账号自助接口使用；它不能与公开访问、全局授权或权限列表组合。SDK 当前版本为 1；平台版本要求通过 platform 核心依赖声明。

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

API 规则保留 `authorities` 的旧任一项匹配行为，不能改解释为 AND。格式 v2 的权限规则必须把本页访问权限放入 `allAuthorities`，并至少要求该页一个已映射操作；`authorities` 只能列该页操作，不能替代页面访问门槛。新增的 `allAuthorities` 表示额外的全部匹配条件，必须是同一插件页面上已声明的访问/操作映射；同时出现两个字段时，旧列表先按任一项匹配，再要求 `allAuthorities` 全部满足。旧格式 v1 和旧 `global:true` 规则继续保持原有匹配及全局范围语义。混合范围路由可额外声明 `globalAuthorities`：其中至少一项必须同时满足普通权限匹配并具有全局范围；该字段是任一项匹配，适用于“页面访问可按本人范围、管理动作必须全局”等组合。它不能放宽 `allAuthorities` 的持有要求。实际服务也必须校验页面访问和动作，并保留已有用户/角色数据范围；manifest 规则不能替代服务的对象级检查。`authenticatedOnly`、公开读取和签名回调规则保持既有语义，不能与权限规则混用。

`GET /api/platform/permission-catalog` 返回平台与当前已安装插件的动态权限组，页面结构为 `{id,path,title,navigationGroup,navigationOrder,accessPermission,operationPermissions,accessScope,operationScopes}`；权限项结构为 `{code,label,requires,editable,retired}`，插件组的 `source` 为 `plugin`。插件页面只从该 route 的显式映射生成，旧清单缺少映射时 `operationPermissions` 为空，不能按组内其他权限补齐。插件权限只在当前已安装 manifest 声明时可新增到角色。首次给角色授予插件权限，仍须通过角色创建或更新的 `platform:roles:create` / `platform:roles:update` 检查，并由持有全局 `platform:modules:install` 的操作者为当前已安装插件提供的权限执行受审计引导；此引导不能授予平台权限、未知权限或已卸载插件权限。卸载后，角色中保留的历史权限会显示为退役条目，只能保留或移除，不能再次新增。

运行时每次刷新都按插件 ID 替换完整目录快照；安装后注册，卸载后移除，避免静态权限集合累积。卸载不会删除角色已保存的 authority；目录会将不再由已安装插件声明的历史授权列为退役权限，供查看现有角色使用。角色可以保留或移除退役权限，但不能新增。插件服务卸载后不再注册路由或执行能力；重新安装后再按当前 manifest 注册权限和页面。

插件生命周期权限按操作拆分：导入包和首次安装需要 `platform:modules:install`，启用已安装插件需要 `platform:modules:update`，卸载需要 `platform:modules:uninstall`。安装操作若同时启用已安装依赖，也要求 update。后台任务执行时会再次检查对应权限。业务 API 的细粒度权限仍由插件 manifest 和服务层共同执行；服务层原有的数据范围、全局范围与事务边界必须保留，不能因拆分 authority 而放宽。

## 后端资源所有权

PF4J 为每个插件创建独立类加载器，框架与 SDK 从宿主加载。`PluginEntry.configuration()` 提供独立 Spring 子容器配置，容器可以使用宿主稳定 Bean。健康检查通过才注册 HTTP 映射并发布能力。

后台任务使用子容器中的 `PluginResources.schedule`，订阅/其他可关闭资源用 `own`。每次托管任务、HTTP 请求和 `PluginExtensionsPort` 扩展调用取得租约，卸载先停止接收并等待租约排空（最多 30 秒）。Spring 容器负责销毁本地 Bean，PF4J 停止/卸载并释放类加载器。禁止自行启用 `@EnableScheduling`、长期持有插件引用或启动未登记线程。

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

升级流程为预检卸载旧版本、导入新版本、预检安装。修改已发布插件时提高 patch 版本，不要用相同版本号导入不同摘要；当前运行时通过卸载、导入、安装新包升级，没有原位替换已安装制品的路径。默认 `PRESERVE` 会保留业务表和已保存角色权限，插件权限目录则在卸载时撤下、安装新包后按新 manifest 重建。向前迁移为新细粒度 authority 添加授权；已发布迁移不可改写。旧权限若要求多个来源共同成立，迁移应把每个旧授权来源分别保存为唯一的 `platform:__v50:<slug>` 隐藏 authority，并在 `platform_permission_projection_component` 注册逻辑权限所需的组合及全局/目标范围；不能将旧组合直接复制成宽泛的新操作。逻辑权限之后的显式新授权保持独立，只有废弃的旧代码才进入退役列表。MFA 升级前要确认可承受短暂卸载期间服务不可用，并按插件说明保留恢复状态；不要为了升级手工删除 MFA 状态或数据库权限记录。移除任何被安装消费者依赖的插件会被阻止，即使消费者已停用。失败任务显示具体阶段，迁移与业务数据保留。

### 固定发布图部署

生产镜像启用 `LUMIRA_PLUGINS_FIXED_GRAPH=true`，插件 ZIP 与 `approved-graph.json` 随同服务器镜像只读发布；`LUMIRA_PLUGINS_APPROVED_GRAPH` 指向该文件，`LUMIRA_PLUGINS_PATH` 指向当前容器私有的可写解包目录。仓库 Docker 配置使用 `/app/plugins/approved-graph.json` 与 `/tmp/lumira/plugins`。不要把 PF4J 的 `loaded/`、`artifacts/` 解包目录挂载为多个容器共享的可写卷。

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

## 卸载数据选择

卸载预检默认 `dataMode=PRESERVE`。`POST /{id}/uninstall-plan?dataMode=DELETE` 返回当前清理范围和记录数量；执行使用 `{revision,dataMode:"DELETE",confirmation:"插件标识"}`。预检 revision 绑定插件、操作、数据模式及记录数量；执行与请求排空后再次核验，改变模式或数据数量后必须重新预检。删除还须在页面输入插件标识并确认不可恢复提示。任务持久化 data_mode，独立 `plugin.dataDeleted` 审计区分永久清理与普通卸载。

安装包 `dataSets` 按顺序声明 `{table,label,column?,value?}`。宿主只允许插件自己的 `plugin_<id>_*` 表，以及三个接管插件已明确归属的业务表；本地化语言仅清理 `built_in=false`。B52 初始化库不包含旧账号草稿表；当前运行时不读取、写入或清理这类数据，旧全局数据库中的草稿不由宿主基线迁移。所有插件卸载数据集在同一数据库事务中清理，失败全部回滚；已卸载的插件保持卸载，任务显示数据清理失败，可重新安装使用保留的数据。

删除只针对管理员选中的插件，闲置自动依赖始终保留数据。工单清理工单、处理记录和附件关联，共享文件保留；本地化清理自定义文案、覆盖翻译、发布记录与自定义语言，内置语言保留；敏感词清理词库、角色管控和命中记录。表结构、迁移/技术版本记录、权限授权、源码和发布包继续保留，重新安装不会通过旧迁移恢复已删除的业务记录。

插件数据库采用[单文件初始化契约](plugin-initialization.md)：每个内置插件只保留一份最终 SQL，旧记录按原身份和校验和验证，现有数据及历史不重放、不改写。
