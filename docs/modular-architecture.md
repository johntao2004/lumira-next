# Lumira 模块化架构

## 结论

当前项目最优的演进路线是“模块化单体（modular monolith）”，不是马上拆成微服务。

项目的宿主与可信插件共享 PostgreSQL，业务写入以本地数据库事务为一致性边界。现在拆微服务会提前引入网络一致性、部署编排、分布式追踪和跨服务迁移成本，却不能解决现有的直接依赖问题。先把模块边界做成可验证的契约，未来需要独立扩容或独立发布时，再把已经稳定的模块提取成服务，迁移成本最低。

## 模块图

```text
                         ┌────────────────────┐
                         │  kernel             │
                         │  common / modules   │
                         │  contracts / ports  │
                         └─────────┬──────────┘
                                   │
              ┌────────────────────┴────────────────────┐
              ▼                                         ▼
       identity(required)                         platform(required)
              │                                         │
              └──────────────┬──────────────────────────┘
                             ▼
        required domain modules (independently described)
   directory / dictionaries / settings / files / events /
   navigation / notifications / monitoring / communication
                             │
                             ▼
                  optional feature modules
             audit-console / feedback / sensitive-words
                         / localization
```

运行时依赖方向为：`identity` 不依赖可停用模块；`platform` 依赖 `identity`；required 域模块依赖 kernel、identity、platform 或其他 required 域模块；可选模块不能成为 required 模块的依赖。模块内部可以继续按 `api / application / domain / infrastructure / web` 分层，但不能用包名掩盖反向依赖。

## 已落地的模块契约

后端：

- `IdentityModuleConfiguration`、`PlatformModuleDescriptorConfiguration`、`CommunicationModuleConfiguration`、`AuditModuleConfiguration` 等由各模块自己贡献 `PlatformModule`，`modules/ModuleConfiguration.java` 只负责组装注册表。
- `PlatformModule` 在创建时校验 ID、版本、依赖、自依赖和重复依赖；`ModuleRegistry` 统一检查缺失依赖、循环依赖和 required 依赖 optional 的错误图。
- `identity/api`、`dictionaries/api`、`notifications/api` 的公共契约不依赖 Spring MVC、Servlet 或 JDBC；领域校验异常由 web 适配器映射为 HTTP 错误，后续可以先拆 API 包再拆应用包。
- 每个 Controller 必须使用 `@ModuleEndpoint` 声明归属。`ModuleEndpointValidator` 在启动时拒绝未归属或引用未知模块的接口，`ModuleAccessInterceptor` 在请求进入时检查可选模块状态。
- 写审计只依赖 `common/AuditPort.java`；审计查询仍由 `audit/AuditRepository` 私有持有。清理调度只依赖 `common/TerminalDeliveryQueue.java`，邮件和短信实现该端口。
- 身份模块只依赖 `common` 与自身 `identity/api` 契约；SMTP、短信凭据、队列和供应商连接都封装在 required `communication` 模块，邮件/短信运维页面也归属于该模块。
- 身份模块对资料架构、字典校验、头像状态、账号归属、密码/验证码/通行密钥/维护策略只依赖 `identity/api` 端口；settings、dictionaries、files 通过适配器提供实现。客户端地址解析也已经下沉到 `common`，文件和反馈模块不再反向依赖 identity 实现类。
- 反馈模块发布站内通知只依赖 `notifications/api/NotificationProducerPort`，不再绑定 `NotificationService`；通知模块可以替换存储、消息格式或未来的异步投递实现，而不牵动反馈用例。
- 反馈策略和资料字段校验只依赖 `dictionaries/api/DictionaryLookupPort`，跨模块不再传递 `DictionaryLookupRepository` 或数据库 ID。
- 反馈图片入口只依赖 `FileContentPort` 与 `UploadRateLimitPort`；图片归一化器和上传限流器都留在 files 模块内部，文件校验错误通过端口语义返回。
- communication 模块的邮件与短信队列共用 `communication/DurableDeliveryStore` 状态机；渠道适配器只提供 payload 类型、表名和网关结果映射，租约续期、claim token fencing、退避、UNCERTAIN 和终态清理不会再分叉。
- communication 模块的邮件与短信 Worker 共用 `communication/DurableDeliveryWorker` 编排；渠道只注入连接源、网关、结果映射和审计前缀，虚拟线程并发上限、总超时、租约续期和保守完成策略保持一致。
- communication 模块读取 SMTP/SMS 配置只依赖 `common/MailConfigurationPort`、`common/SmsConfigurationPort`；凭据解密、设置版本和设置存储仍由 settings 适配器负责，通信模块不再直接依赖 `SettingStore`、`SecretStore` 或设置 DTO。
- `scripts/module-boundaries-smoke.py` 已接入 `pnpm test`，检查模块描述、Controller 归属、公共层反向依赖和前端壳层耦合。
- 同一检查会在静态阶段复现模块注册表的依赖约束：依赖必须存在、不能形成环、必选模块不能依赖可选模块；这样模块图错误不会等到 Spring 启动后才暴露。
- 同一检查会按后端模块描述校验源码 import：跨模块依赖必须出现在 `PlatformModule.dependencies`，只有 kernel/common 可作为无方向共享层。
- 同一检查还会逐项比对后端 `PlatformModule` 与前端 `manifest.ts` 的 ID、版本、required 标记和依赖集合，避免两端模块图静默漂移。
- `ModuleRuntime` 是后台任务共用的生命周期端口；审计清理和敏感词策略都会在模块关闭后停止生效，不只依赖 HTTP 拦截器。
- HTTP 模块拦截器同样只依赖 `ModuleRuntime`，不再直接依赖 `ModuleStateStore`；请求路径只读取目标模块的状态，避免每次可选模块请求加载完整状态列表。
- 管理写权限复核使用按权限域确定性的 advisory lock 分桶；模块生命周期和设置/资料共享边界保留 481026，其余文件、通知、字典、反馈等不同域可以并行写入。`requireGlobal` 纯读检查不取锁，管理员文件列表/详情也不再误取写锁。
- 会话能力和运行时导航只依赖只读 `ModuleCapabilityPort`，不泄漏模块状态记录；只有模块管理/包激活用例需要完整状态版本与修订历史。
- 模块管理 Controller 对外返回 application DTO，不直接暴露 `ModuleStateStore` 的持久化记录；包激活只读取启用能力快照。
- `SecretKeys` 缺失时仍允许不依赖密钥的功能启动，但会在启动阶段输出明确告警；生产环境可在部署策略中把该告警升级为 fail-fast。
- 原来挂在 `platform` 大模块下的字典、文件、设置、目录、事件、导航、通知、监控已各自拥有后端描述符和前端 manifest；`platform` 现在只保留平台壳、模块生命周期和认证入口。
- 所有后端 Controller 都必须归属于其包对应的模块；目录模块暂时复用 identity 包中的在线会话实现，但该跨包归属已作为唯一显式例外记录在边界检查中，后续移动实现时无需改 API。
- `backend/src/main/resources/db/module-ownership.json` 为当前 B52 初始化结构建立表、视图和函数的模块归属清单；边界检查会确保清单覆盖基线对象，数据库 smoke 会确保实际库没有未归属的 `platform_*` 表。
- 监控的模块总数和启用数读取模块注册能力快照，而不是只统计可选模块状态表，避免 required 模块拆分后仪表盘出现假数据。

前端：

- `platform/modules/types.ts` 定义模块、路由和页面渲染器契约。
- `platform/api/client.ts` 只负责网络传输、CSRF 和统一错误载体；身份、模块、导航、目录和分页 DTO 分别由对应平台域的 `api-types.ts`/`pagination.ts` 持有，避免形成前端类型上帝文件。
- `features/*/module.tsx` 由功能模块自己声明路由、权限、依赖和页面；`app/dashboard-modules.tsx` 负责宿主拓扑排序，`platform/modules/contracts.ts` 与 `page-store.ts` 负责重复路由检查和页面注册。
- `app/dashboard-workspace.tsx` 只消费注册表，不再直接导入所有业务页面；当前只创建和渲染活动页面，减少壳层重渲染工作。
- 页面模块通过 `React.lazy` 按路由加载；注册表仍在启动时校验所有路由归属，但首屏不会静态加载未访问的业务页面。
- `routes.ts` 由模块描述生成，新增模块页面不再需要修改一个巨大的中央路由表。
- 所有 `ResourceTable` 页面和抽屉通过 `platform/routing/use-list-request.ts` 共享分页、页大小和搜索状态；需要浏览器历史的页面继续使用同一契约的 `useListLocation`，避免每个 CRUD 页面重新发明列表状态。

## 后续的真实拆分顺序

### 1. 端口替换已完成，新增跨模块调用必须延续同一规则

本轮已把高风险的历史反向依赖替换为端口；后续如果出现新的跨模块调用，仍应先抽端口，再移动文件。当前契约如下：

| 当前耦合 | 应抽出的端口 | 目标实现 |
| --- | --- | --- |
| identity → settings.ProfileSchemaReader | 已完成 `ProfileSchemaPort` | platform/settings adapter |
| identity → dictionaries/files | 已完成 `ProfileDictionaryPort`、`AvatarStatusPort` | platform/dictionaries、platform/files adapter |
| files → identity.ProfileRepository | 已完成 `AccountOwnerPort` | identity/ProfileRepository adapter |
| identity → 安全/维护/通行密钥设置 | 已完成 `SecurityPolicyPort`、`MaintenancePolicyPort`、`PasskeyPolicyPort` | platform/settings adapter |
| feedback → dictionaries/files/sensitive/notifications | 已完成 `DictionaryLookupPort`、`FileContentPort`、`ContentPolicyPort`、`NotificationProducerPort` | 各自模块适配器 |
| mail/sms → duplicated queue state machine | 已完成 `DurableDeliveryStore<P>` | communication shared infrastructure |
| mail/sms → settings concrete services | 已完成 `MailConfigurationPort`、`SmsConfigurationPort` | platform/settings adapters |
| mail/sms → duplicated worker orchestration | 已完成 `DurableDeliveryWorker<P,C,R>` | communication shared infrastructure |
| platform/common → 具体队列 | 已完成 `TerminalDeliveryQueue` | mail/sms module |
| 业务模块 → AuditRepository | 已完成 `AuditPort` | audit module |

端口只放稳定的输入、输出和错误语义，不把 JDBC、Spring MVC、数据库记录类型泄漏到端口。每新增一个端口，都要删除调用方对具体实现的 import，并给端口增加单元测试和适配器测试。

### 2. 再把模块内部统一成五层

新代码按以下结构进入对应模块；旧代码迁移时一次只迁一个用例：

```text
module/
  api/             对其他模块公开的端口、命令、只读 DTO
  application/     用例编排、事务边界、权限策略
  domain/          不依赖 Spring/JDBC 的规则和值对象
  infrastructure/ JDBC、加密、外部网络、定时任务
  web/             Controller、请求校验、响应映射
```

Controller 不直接访问 Repository；跨模块不传递另一个模块的 Repository、Entity 或 `JdbcClient`。后台任务属于 infrastructure，只通过 application/api 进入模块。

### 3. 单文件初始化保留模块归属

`B52__platform_initialization.sql` 是当前全局数据库唯一初始化来源。`db/module-ownership.json` 标出每个表、视图和函数的模块归属；宿主 Flyway 只扫描初始化目录：

```text
db/migration/B52__platform_initialization.sql
```

当前宿主初始化不提供从已删除的全局迁移历史升级的路径。CI 核对 B52 文件及当前表、视图、函数的模块归属；数据库功能测试从初始化 SQL 提取当前对象定义，避免另存会漂移的历史 DDL。已有其他全局迁移历史的环境不能通过当前 Flyway 位置升级；恢复或重新初始化必须按单独批准的数据库操作流程处理，不自动删除数据或执行 repair。插件自有数据库对象继续由各插件的 Flyway 位置和 history table 管理；已发布的插件迁移保持不变，新版本只在所属插件目录追加。

### 4. 最后再拆 Maven 模块

当端口替换完成、边界测试稳定后，再将 Maven 拆成：

```text
backend/
  lumira-kernel/
  lumira-identity/
  lumira-platform/
  lumira-communication/
  lumira-audit/
  lumira-optional/
  lumira-app/
```

`lumira-app` 只负责 Spring Boot 启动和模块装配。拆分前必须先做到“删除一个模块的依赖，编译就失败且错误位置清晰”；否则 Maven 多模块只是把一个大模块搬成多个目录，反而增加构建复杂度。

## 模块完成标准

一个模块只有同时满足以下条件，才算真正独立：

1. 有自己的 `PlatformModule`/前端模块描述、版本和依赖。
2. 所有 HTTP 接口都有归属，启停状态能阻断接口和后台任务。
3. 有自己的迁移归属、权限清单、审计动作和测试夹具。
4. 对外只暴露 api/port，不暴露 Repository、实体和数据库连接。
5. 可单独关闭其页面、接口、定时任务和消息消费者，并且不影响 required 模块。
6. `pnpm test`、后端测试、构建和真实 HTTP/浏览器回归均通过。

## 2026-09-16 增量验证（历史记录）

以下只记录当时的实现和检查结果，不代表当前 B52 初始化结构、当前全局 Flyway 配置或旧全局数据库升级能力。当前结构与流程以本文件上文和 README 中的 B52 说明为准。

- `pnpm test`：当日 20/20 通过；其中包含模块边界检查和模块图的重复、缺失、循环依赖、required→optional 依赖测试。
- 模块边界检查：当时 15 个后端/前端模块描述符一致，48 个 Controller 均有明确归属；当时 V1 的 37 张平台表和 8 个函数/视图均有数据库归属。
- identity 的跨包源码检查：已阻止对 settings、dictionaries、files 具体实现的回退依赖，并验证 7 个 identity-facing 端口适配器存在；feedback、files、dictionaries、notifications、communication 的端口也纳入同一边界检查。
- 前端模块边界检查：已验证页面模块使用懒加载，禁止模块注册文件静态导入业务页面。
- 前端平台层：已统一日期格式化与无效日期回退，并缓存 `Intl.DateTimeFormat`；错误码白名单由平台 API 工具集中处理。
- 前端平台层：已统一资源列表状态初始化；边界检查会阻止功能页再次内联 `page/pageSize/search` 默认值，避免 CRUD 页面产生隐性分叉。
- 导航模块契约：服务端只接受已注册模块 ID，并在运行时节点中回传模块归属，避免菜单引用未安装模块后静默消失。
- 公共 API 边界检查：已验证三组 `api` 契约不泄漏 Spring MVC、Servlet 或 JDBC 类型。
- `pnpm typecheck`、`pnpm build`：通过；Next.js 生产构建继续只生成统一的动态 dashboard 壳。
- `python3 scripts/database-schema-smoke.py`：当日通过；本次没有修改当时已执行的 V1 基线。这不是当前 B52 验收结果。
- 算法选择见 [算法基线](algorithm-baseline.md)；当前源码的验证命令见 [README](../README.md) 与 [云端开发说明](cloud-development.md)。

这套标准比“每个目录都叫 module”更严格，也为未来按模块独立部署保留了清晰的升级路径。

## 可执行的插件源码边界

`scripts/plugin_boundaries.py` 从正式插件及 `plugins/sdk/examples/*/plugin.json`
发现源目录，不假设 Java 包都在 `com.lumira.platform` 下。每个插件保留自己的
命名空间和实现；宿主公共入口限定为 `common`、`modules`、明确的模块 `api`
契约以及共享 `content.RichText`。跨插件实现不能直接导入，应使用 SDK SPI。
使用身份、字典或通知模块的公共 API 仍须在清单声明对应依赖。
每个插件 Controller 必须声明自身 `@ModuleEndpoint`，样例遵循同一规则。

该检查同时进入模块边界测试及插件打包入口；负例测试覆盖宿主私有实现、
跨插件实现、未声明 API 依赖及缺失/错误接口归属。这是源码架构约束，不是
对同进程可信 Java 插件建立安全沙箱；插件仍与宿主共享进程和数据库权限。


## 七区职责与实际入口

分区是同一产品的职责边界，不按图拆成七个服务，也不改变 required 模块或已有业务能力。

| 分区 | 实际入口与约束 |
| --- | --- |
| 页面与宿主装配 | `apps/web/src/app/dashboard-modules.tsx` 装配静态模块，`dashboard-workspace.tsx` 组合页面；`features/*` 实现业务页面。 |
| 接入与静态资源 | 现有反向代理、SPA 静态发布与 HTTP 路由保持兼容。 |
| HTTP 与权限 | 各模块 Controller 处理协议和校验；业务服务的 guard/data-scope 继续复核，前端隐藏按钮不能代替服务端授权。 |
| 功能模块 | 宿主模块和 `plugins/*` 以明确的 public API / SDK 协作，不导入其他模块的私有仓储。 |
| 公共能力 | `common`、`modules` 提供宿主内开发契约；`plugins/PluginRuntime` 管理可信插件生命周期；事件、审计、密钥等能力由既有服务承担。 |
| 数据存取 | Repository / Store 在业务事务中读写共享 PostgreSQL；迁移只向前演进，不修改已应用迁移的校验和。 |
| 运行基础 | PostgreSQL、兼容旧文件的存储路由、隔离的 Office worker；Office 解析失败不能静默降级为宿主解析。 |

动态页面的存储 `platform/modules/page-store.ts` 不导入宿主组合根。加载器 `plugin-loader.ts` 根据当前 script 对象识别每次加载，卸载、换 digest/version、失败和最后一个使用者退出时回收该次资源。旧脚本不能认领后续相同 ID 的加载。注册 ABI 保持 `(id, bundle)`；页面授权元数据优先采用当前已加载插件版本。静态插件 manifest 只作为预加载的目录元数据，不引入插件页面实现。

宿主组合只初始化一次。开发热更新如重建组合根，应完整刷新页面；生产运行不动态重建静态宿主注册表。

插件卸载先停止接收调用并排空，再撤销路由、资源、子容器和类加载器。每一步清理都必须尝试，异常在清理完毕后传播。PRESERVE 与 DELETE 的安装状态、图批准及成功记录在事务中一起提交；DELETE 额外删除声明的数据。提交前失败需要恢复已卸载实例，恢复失败时保持不可就绪。

事件业务写入、消费副作用与 ACK 继续使用既有本地事务。当前消费者由宿主配置注册；不能假定任意动态插件子容器的 Consumer Bean 会自动热订阅。新增动态订阅机制需要另行设计卸载排空、恢复及无消费者语义。

可执行检查包括 `pnpm test`、`pnpm typecheck`、`pnpm plugins:typecheck`、`pnpm build`、`python3 scripts/plugin_boundaries.py` 和 Java 单元/数据库测试。SDK 包含 8 个正式插件和 3 个样例的源边界检查；插件构建还拒绝通过相对路径导入宿主私有实现。可信插件仍与宿主同进程，这些约束不是安全沙箱。
