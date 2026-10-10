# 标签页与 URL 锚点

后台 Tab 的选中状态以 URL fragment 为准。主标签组使用 `#tab=<key>`；同一页中的独立标签组使用 `tab.<scope>`，例如：

- `/dashboard/watermark#tab=watermark`
- `/dashboard/files#tab=trash`
- `/dashboard/agreements#tab=agreements&tab.user-agreement=preview&tab.privacy-agreement=edit`

切换 Tab 使用 Umi history push；首次访问、缺失锚点或无效选项使用 replace 补齐默认值，不增加无意义的历史记录。刷新、直接打开链接和浏览器前进/后退均从 URL 恢复选中项。只接受当前可用选项，权限过滤后的列表不包含的 key 会回退。锚点仅记录位置，不保存未提交的表单内容。

`RouteTabs` 保留每个页面既有路由，自动使主标签锚点与路由一致。同页标签使用 `AnchorTabs`，需要用选中项控制父组件查询的页面使用 `useTabAnchor`。多组标签必须设置稳定且唯一的 scope，不使用 React 动态实例编号或界面翻译作为标识。保留内容但当前不可见的标签组通过 `enabled` 停止初始化 URL。

通知弹层还使用 `inbox=open` 记录打开状态；用户编辑抽屉使用 `user=<UUID>`，新增用户使用 `user=create`。刷新时恢复弹层上下文及内部标签，关闭时只清除打开状态。恢复用户抽屉仍需通过已有页面权限及用户详情接口验证，不改变服务端权限。

新增标签组须接入上述组件或 hook。地址更新合并当前 fragment，保留其他标签组和查询参数；未知或编码异常的选项不会用作页面、组件或接口路径。URL 工具的行为由 `tests/tab-anchor.test.mjs` 验证，实际 Tab、刷新与历史导航需另行浏览器验收。

侧边栏状态独立于 Tab、查询参数和 URL fragment。`Workspace` 控制菜单分组的 `openKeys`；同组路由切换保留用户手动展开或关闭的分组。切换到其他业务分组时只展开新分组，保留已经展开的分组；权限或模块变化时清除不再可见的分组。整条侧边栏的展开/收起仍由用户按钮控制，折叠再展开也保留分组位置。移动布局统一以 767px 断点决定，桌面不接受组件内部移动布局初始化造成的自动收起。
