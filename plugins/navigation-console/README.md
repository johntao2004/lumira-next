# 导航管理插件

提供既有 `/dashboard/navigation` 页面及 `/api/platform/navigation/menus` 管理接口。依赖 `platform@1.0.0` 和 `navigation@1.0.0`，用 `node scripts/build-plugins.mjs navigation-console` 构建独立包。

写入、排序、版本冲突、内置菜单保护、全局权限校验和审计由插件执行。运行菜单的只读接口 `/api/platform/navigation` 及无递归树投影属于内核；管理插件卸载后继续按当前权限、模块状态读取已有菜单。

卸载保留菜单标识、父子关系、文案、排序和版本。不声明删除数据集，不改写 B52 或已应用历史。共享 ModuleCatalogPort 校验已注册模块标识，不要求所引用模块当前运行。
