# 导航分组约定

运行时导航以 `platform_navigation_menu` 为事实源。内置目录的稳定 `menu_code`、排序、默认文案和宿主页面种子由宿主唯一的 B52 最终态基线定义；插件页由 `plugin.json` 的 `routes[].group` 声明归属，安装时按目录 `menu_code` 关联。运行时导航调整会保留管理员自定义目录、手动移动页面的父目录/顺序，以及每行的 `enabled`、`deleted` 状态。已知内置锚点按固定身份维护文案键；支付渠道页的读取权限与 global 范围按共享 payment-core API 要求修正，即使页面已被手动移入自定义目录也会同步。

`communication` 与 `payments` 是保留的内置目录代码。B52 初始化基线创建这些内置目录；自定义菜单不要复用保留代码。当前宿主不提供旧全局导航升级脚本，带旧全局迁移历史的数据库须按数据库初始化说明显式重建。

| 目录 ID | 文案键 | 默认职责 |
| --- | --- | --- |
| `overview` | `overview` | 工作空间：工作台、我的文件 |
| `ai-management` | `aiManagement` | AI 中心 |
| `user-management` | `userManagement` | 用户、组织、角色、资料字段、认证与安全、MFA 策略 |
| `content-management` | `contentManagement` | 全部文件、字典、敏感词、打印模板、本地化 |
| `communication` | `communicationCenter` | 通知、邮件/短信渠道与投递、工单反馈 |
| `payments` | `paymentManagement` | 支付订单与支付渠道设置 |
| `operations` | `operationsCenter` | 监控、API 文档、审计、事件 Outbox |
| `administration` | `administration` | 模块、站点品牌、水印、协议、维护、导航配置 |

新增插件页面时，`routes[].group` 必须使用上表中的目录 ID，`permission` 与 `global` 要和页面实际调用的服务端权限范围一致。插件可以引用其声明依赖提供的页面读取权限。改变既有插件路由归属时同步更新 `plugin.json` 和前端模块清单、提升插件版本，并更新依赖方的精确版本；不要改写已发布插件迁移。

新增或调整宿主内置页面时，更新 B52 中相应的初始化种子，并同步维护导航契约和测试；若同时改变表、视图或函数，更新 `backend/src/main/resources/db/module-ownership.json` 的对象归属。当前仓库不维护宿主全局导航增量迁移。锚点页的 `label_key` 和默认文案由服务端导航记录维护，必须与前端静态文案键保持一致。插件路由仍通过各插件 manifest 管理；改变已发布插件路由时提升插件版本并同步依赖方，不改写已发布插件迁移。

角色权限目录的页面元数据用于展示授权选择：`accessPermissions` 映射打开页面所需的现有权限，`operationPermissions` 映射该页实际操作使用的现有权限，`sharedPermissions` 标出跨页复用、需要同步选择的权限。插件页的访问权限已有映射时保持 `MAPPED`；未声明归属的操作留在原权限组的未映射项中。只有缺少页面访问映射时才使用 `UNASSIGNED` 页面状态；目录元数据不新增权限，也不改变服务端守卫。
