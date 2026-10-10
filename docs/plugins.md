# 插件开发与运行

## 目录与构建

根目录 `plugins/sdk/` 是稳定契约；`plugins/feedback/`、`plugins/localization/`、`plugins/sensitive-words/`、`plugins/mfa/`、`plugins/diffuse-avatar/` 各自包含 README、plugin.json、backend、frontend、migrations 和构建生成的 dist。每个 README 明确其插件用途、依赖、构建和安装方法。MFA 的认证状态与接口见 [二次认证说明](mfa.md)。

使用 Node 24、pnpm 10.33.0、JDK 25：

```sh
pnpm plugins:typecheck
pnpm plugins:build
# 另行构建从未进入宿主构建的示例
pnpm plugins:build sdk/examples/hotplug-probe
```

构建命令先编译 SDK/宿主契约，再 javac 编译插件、生成 PF4J JAR、编译共享 React/Ant Design 的页面入口、复制迁移并生成 SHA-256 文件清单。构建 ZIP/JAR 固定时间戳，以便相同源码重复构建产生相同包。编译产物被 Git 忽略。

私有 Java 依赖放 `backend/lib/*.jar`；构建时加入编译类路径并复制到包。前端私有库由 esbuild 打包，宿主模块清单见 `plugins/sdk/frontend-modules.json`。存在于 `plugins/<dependency-id>/plugin.json` 的插件依赖会先构建并递归放进 `dependencies/*.lumira-plugin.zip`；核心依赖无需打包。离线依赖版本必须精确匹配清单。

宿主框架和 SDK 由平台提供，安装包不能重复覆盖。插件通过 common 与各模块 api 契约调用平台，禁止引用其他插件实现。SDK v1 包括认证、全局范围、权限、JdbcClient/事务、审计、文件、通知、字典、AI 文本生成和可选扩展调用。前后端 SDK 应按兼容契约演进。

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

管理操作执行认证、CSRF、模块权限和全局范围检查。动态 Controller 必须声明自己的 `@ModuleEndpoint`，HTTP 方法及授权规则必须在 manifest 登记；实际业务服务继续执行自己的数据范围检查。公开接口只允许显式登记的 GET。页面权限与模块状态共同控制导航，动态页面不授予任何权限。

## 后端资源所有权

PF4J 为每个插件创建独立类加载器，框架与 SDK 从宿主加载。`PluginEntry.configuration()` 提供独立 Spring 子容器配置，容器可以使用宿主稳定 Bean。健康检查通过才注册 HTTP 映射并发布能力。

后台任务使用子容器中的 `PluginResources.schedule`，订阅/其他可关闭资源用 `own`。每次托管任务、HTTP 请求和 `PluginExtensionsPort` 扩展调用取得租约，卸载先停止接收并等待租约排空（最多 30 秒）。Spring 容器负责销毁本地 Bean，PF4J 停止/卸载并释放类加载器。禁止自行启用 `@EnableScheduling`、长期持有插件引用或启动未登记线程。

账号头像扩展使用稳定的 `common.GeneratedAvatarPort` 契约：启用的插件可以提供 `generate(UUID accountId)`，返回 PNG 字节；宿主在持有插件调用租约期间使用结果，不缓存插件 Bean。`diffuse-avatar` 依赖 `identity@1.0.0` 和 `files@1.0.0`，生成确定性的 256 × 256 弥散渐变头像，不提供业务路由或权限。

Flyway 使用每插件 `plugin_<id>_schema_history` 独立记录，仅执行向前迁移；现有表通过接管迁移处理。迁移必须适合事务执行。卸载不删表、不删除发布包，也不删除源码。管理员默认选择保留数据，也可显式选择删除所选插件的业务记录。

## 前端与扩展

`FrontendModule` 导出页面注册，动态脚本使用共享 SDK。安装后加入页面与导航；卸载后撤销注册、卸载 React 组件、删除脚本/CSS、清理所属查询缓存并刷新身份/导航。本地化卸载后恢复内置文案；敏感词卸载后动态内容策略提供者撤下，再安装恢复原词库和角色策略。

前端插件必须在 effect 清理中撤销自己的事件、订阅和定时器。插件私有资源不能绕过 SDK 全局登记。动态加载不需要 Umi 重构建或平台进程重启；页面刷新会按当前安装状态重新加载。

## 运维与验证

首次升级前按项目备份流程保存数据库。先 `pnpm plugins:build` 再启动平台，以便首次转换旧模块状态。生产应保存持久的 `.runtime/plugins/` 制品、数据库与对应宿主 SDK；不能只复制插件源码替代运行制品。

升级流程为预检卸载旧版本、导入新版本、预检安装。兼容迁移后的数据自动恢复；移除任何被安装消费者依赖的插件会被阻止，即使消费者已停用。失败任务显示具体阶段，迁移与业务数据保留。

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

发布包含包图或插件状态变化的新镜像前，先停止并排空旧运行节点，再启动新图。V38 起新节点会登记心跳、已加载图摘要和每项插件任务的 owner lease；新镜像在数据库中发现不同的已就绪旧图时不会覆盖共享插件目录或接受请求，节点健康/业务 API 会保持失败关闭。每个应用节点必须使用独立的插件解包根目录；QA 第二节点示例根目录为 `.runtime/role-authentication/node-b/plugins`，只复制同一批准的包与锁文件，不共享另一个节点的可写解包目录。V38 迁移会将超过 5 分钟且没有 owner lease 的旧排队任务标记为中断；新任务只由 owner 节点续租，owner lease 到期后可被恢复为失败。

V38 节点检测依赖这些节点已运行并登记心跳；升级期间仍运行的旧版实例不能由该机制探测。因此首次迁移或升级时必须人工确认旧实例已停止，不能把双节点本地 QA 当作生产负载均衡、容器编排、数据库故障切换或持续可用性的证明。生产 Docker/代理/数据库部署仍需在目标环境单独验证。

`pnpm test:module-packages` 执行真实 HTTP/数据库生命周期回归，使用独立 `qa-plugin-*` 制品，不改变业务记录。测试后重启用于清空测试制品的内存目录。示例 hotplug-probe 应在宿主启动后构建、导入，验证此前不存在的 Java 接口与前端页面立即可用且宿主 PID 不变。浏览器验收与 HTTP/构建检查分别执行，截图和测试日志保存在 `.runtime`。

`scripts/mfa-smoke.py` 及其 `--passkey-only`、浏览器夹具选项属于历史 MFA 验收工具。新版首次绑定、恢复票据和再次验证已经改变其前提，运行前必须复核端点和隔离环境；不能将旧脚本通过视为新版完整验收，也不能在主库真实账号上运行凭据变更测试。合成 WebAuthn assertion 与实体认证器、真实投递和多节点验收分别记录。详见 [MFA 说明](mfa.md)。

## 卸载数据选择

卸载预检默认 `dataMode=PRESERVE`。`POST /{id}/uninstall-plan?dataMode=DELETE` 返回当前清理范围和记录数量；执行使用 `{revision,dataMode:"DELETE",confirmation:"插件标识"}`。预检 revision 绑定插件、操作、数据模式及记录数量；执行与请求排空后再次核验，改变模式或数据数量后必须重新预检。删除还须在页面输入插件标识并确认不可恢复提示。任务持久化 data_mode，独立 `plugin.dataDeleted` 审计区分永久清理与普通卸载。

安装包 `dataSets` 按顺序声明 `{table,label,column?,value?}`。宿主只允许插件自己的 `plugin_<id>_*` 表，以及三个接管插件已明确归属的业务表；共享草稿表只能精确清理 `draft_key=feedback.create`，本地化语言仅清理 `built_in=false`。清单没有声明清理范围的包不能选择删除数据。所有数据集在同一数据库事务中清理，失败全部回滚；已卸载的插件保持卸载，任务显示数据清理失败，可重新安装使用保留的数据。

删除只针对管理员选中的插件，闲置自动依赖始终保留数据。工单清理工单、处理记录、附件关联和工单草稿，共享文件保留；本地化清理自定义文案、覆盖翻译、发布记录与自定义语言，内置语言保留；敏感词清理词库、角色管控和命中记录。表结构、迁移/技术版本记录、权限授权、源码和发布包继续保留，重新安装不会通过旧迁移恢复已删除的业务记录。
