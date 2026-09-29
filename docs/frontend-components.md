# 前端共享组件规范

公共组件位于 `apps/web/src/platform/components`，功能页面位于 `apps/web/src/features`。新功能先复用公共能力，修复交互问题优先落在公共组件，不在页面中复制状态和事件逻辑。

## 抽屉与弹层

- 展示型抽屉使用 `PlatformDrawer`；提交型抽屉使用 `FormDrawer`，提供唯一 `formId`。Ant Design 组件配置按项目已安装版本的类型定义使用。
- 抽屉宽度允许按功能设置，但 wrapper 最大宽度不得超过容器。宽表格在抽屉内滚动，不能裁切标题或关闭入口。移动验收同时检查页面溢出和抽屉矩形边界。
- Escape 优先交给正在交互的子组件。共享抽屉仅处理来自自身 DOM 内部、未被处理的 Escape；嵌套 Portal 保留自己的事件顺序。尊重 `keyboard=false`、IME 组合状态和组合结束后 200ms 保护期。
- 关闭后焦点返回有效的启动入口；嵌套抽屉返回父窗口内入口，不能抢夺另一个打开窗口的焦点。业务页不得增加无条件的全局 Escape 监听。
- 提交期间通过 `pending` 禁用提交/取消/关闭，并由 `onClose` 再次检查状态。只读和加载态分别使用提交禁用，避免把用户困在窗口中。
- 验收覆盖：菜单方向键、Tab、Escape、快速连续开关、焦点返回、嵌套窗口、输入法和下拉框先关闭。`scripts/account-keyboard-browser-smoke.mjs` 默认跑桌面/手机两种布局；设置 `KEYBOARD_STRESS=1` 重复手机双层场景八轮。

## 表单与列表

- 列表复用 `ResourceTable`、`ListRequest`、`PageResult` 和共享 API 客户端；服务端执行分页/权限/边界校验。已有数据刷新失败时保留数据并显示错误，允许重试。
- 保存操作与所属区块绑定，不得提交其他区块的未保存内容。密码字段复用 `NewPasswordFields`，敏感输入按流程清空。
- 文案进入八语言消息表；账号安全动作复用 `accountSecurityActions`。服务器和客户端提供的文本按普通文本呈现，不注入 HTML。
- 测试结论按覆盖范围描述；构建通过不能代替真实浏览器交互验收，一次复跑通过不能单独证明偶发问题已修复。

## Shared account drafts

- `platform/drafts/use-account-draft.ts` owns requests, UUID revisions and conflicts. Mount an independent instance per editor/key; no global account-content cache.
- `DraftActions` shares manual save, confirmed restore/delete, reload, errors and timestamps. It reuses AsyncButton and eight-language messages. No silent restore or automatic form submission.
- Each form supplies a versioned encode/decode whitelist. Never persist passwords, tokens, permissions or live record versions. Validate restored data before applying it.
- Pass busy state to FormDrawer and disable submission during draft writes. Successful restore marks the consumed revision. After successful resource creation, clearConsumed deletes only that revision. Cleanup errors must not report the already successful resource submission as a failure.
- Organization creation is the first adapter. Existing organization edits retain server values/version protection. Other public forms require separate field-safe adapters.
