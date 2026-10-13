# 导航分组约定

运行时导航以 `platform_navigation_menu` 为事实源。内置目录的稳定 `menu_code`、排序和默认文案由宿主 Flyway 管理；插件页由 `plugin.json` 的 `routes[].group` 声明归属，安装时按目录 `menu_code` 关联。运行时升级只调整仍位于旧默认目录中的已知内置或插件路由，保留管理员自定义目录、手动移动页面的父目录/顺序，以及每行的 `enabled`、`deleted` 状态。已知内置锚点仍按固定身份更新文案键；支付渠道页的读取权限与 global 范围按共享 payment-core API 要求修正，即使页面已被手动移入自定义目录也会同步。

`communication` 与 `payments` 是保留的内置目录代码。若升级时同名代码已被自定义菜单占用，V47 会明确失败，等待管理员先处理冲突，不会把该菜单改造成内置目录。

| 目录 ID | 文案键 | 默认职责 |
| --- | --- | --- |
| `overview` | `overview` | 工作空间：工作台、我的文件 |
| `ai-management` | `aiManagement` | AI 中心 |
| `user-management` | `userManagement` | 用户、组织、角色、资料字段、认证与安全、MFA 策略 |
| `content-management` | `contentManagement` | 全部文件、字典、敏感词、本地化 |
| `communication` | `communicationCenter` | 通知、邮件/短信渠道与投递、工单反馈 |
| `payments` | `paymentManagement` | 支付订单与支付渠道设置 |
| `operations` | `operationsCenter` | 监控、API 文档、审计、事件 Outbox |
| `administration` | `administration` | 模块、站点品牌、水印、协议、维护、导航配置 |

新增插件页面时，`routes[].group` 必须使用上表中的目录 ID，`permission` 与 `global` 要和页面实际调用的服务端权限范围一致。插件可以引用其声明依赖提供的页面读取权限。改变既有插件路由归属时同步更新 `plugin.json` 和前端模块清单、提升插件版本，并更新依赖方的精确版本；不要改写已发布插件迁移。

新增或调整宿主内置页面时，追加更高版本的 navigation 迁移并在 `backend/src/main/resources/db/module-ownership.json` 登记所有者。迁移应按固定 `menu_code` 与原目录识别待移动页面，不覆盖自定义页面或已手动移出默认目录的页面，不重置用户的启停、软删除状态。锚点页的 `label_key` 和默认文案也由服务端导航记录维护，必须与前端静态文案键保持一致。

角色权限目录的页面元数据用于展示授权选择：`accessPermissions` 映射打开页面所需的现有权限，`operationPermissions` 映射该页实际操作使用的现有权限，`sharedPermissions` 标出跨页复用、需要同步选择的权限。插件页的访问权限已有映射时保持 `MAPPED`；未声明归属的操作留在原权限组的未映射项中。只有缺少页面访问映射时才使用 `UNASSIGNED` 页面状态；目录元数据不新增权限，也不改变服务端守卫。
