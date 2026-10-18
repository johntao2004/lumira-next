# Lumira 插件 SDK v1

插件是可信自研扩展，在宿主 JVM 和浏览器中执行。每个插件包含源码及独立安装包；平台安装时不编译源码、不联网获取 Maven/npm 依赖。

后端入口继承 `com.lumira.plugin.sdk.PluginEntry`，返回独立 Spring 配置，提供 `checkHealth()`。宿主提供 Spring/Jackson、事务、JdbcClient 及 `common`、`identity/api`、`dictionaries/api`、`notifications/api` 稳定契约。可选能力通过 `PluginExtensionsPort` 的租约调用，禁止保留插件对象引用。HTTP Controller 使用自己的 `@ModuleEndpoint`，每个接口必须在清单中声明方法、路径和权限。

前端入口导出 `FrontendModule`。SDK 可用模块列在 `frontend-modules.json`，共享同一 React、Ant Design、查询缓存和平台组件；插件不得导入其他 feature 的实现。插件页面和所有副作用须遵循 React 卸载清理。

`plugin.json` 声明 ID、精确依赖版本、SDK 版本、入口、权限、页面和 HTTP 授权规则。规则可用 `authenticatedOnly: true` 允许任一非匿名的已登录账号访问自助功能，而不授予具体功能权限；该标志不能与公开访问、全局授权或权限列表组合。构建工具生成每个文件的 SHA-256 清单。已有核心依赖复用；离线插件依赖放进安装包的 `dependencies/*.lumira-plugin.zip` 并登记文件摘要。私有第三方 Java 库放在 `backend/lib/*.jar`，统一构建时编译引用并复制到安装包内，禁止覆盖宿主框架和 SDK 类；前端私有库须随插件编译。

插件只执行向前、事务兼容的数据库迁移；卸载保留表结构和迁移记录。业务数据默认保留，管理员可选择删除清单声明的数据。不得由 `start` 或 Bean 构造器执行不可补偿的业务写入、启动未托管线程或全局注册。资源通过 Spring 容器管理并在销毁时释放。

清单 `dataSets` 按外键删除顺序声明 `{table,label,column?,value?}`。自研插件表使用 `plugin_<id下划线形式>_*` 命名，不能声明宿主表或迁移历史表。共享表仅允许宿主显式认定的插件数据范围。清理不执行插件自带任意 SQL；宿主用声明生成参数化查询和删除，在统一事务中执行。省略清单时仅支持保留数据卸载。详见 [插件运行契约](../../docs/plugins.md)。

后台任务通过子容器提供的 `PluginResources.schedule` 注册；监听器和其他可关闭资源用 `own` 登记。任务与 HTTP 共用排空计数，卸载后取消调度并释放资源。禁止自行启用 `@EnableScheduling` 或创建未登记线程。

插件查询缓存键应以插件 ID 为前缀。平台卸载时按该前缀清理；自定义组件的事件和订阅仍需在 React effect 清理中撤销。

插件数据库采用[单文件初始化契约](../../docs/plugin-initialization.md)：每个内置插件只保留一份最终 SQL，旧记录按原身份和校验和验证，现有数据及历史不重放、不改写。
