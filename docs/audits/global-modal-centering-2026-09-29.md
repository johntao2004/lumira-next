# 全局 Modal 居中修复草案

日期：2026-09-29

## 目标

所有全屏对话框的面板都以浏览器可视区域为参照，水平和垂直居中。抽屉继续从侧边展开；Popconfirm、Popover、Select 下拉框继续靠近触发控件。

## 当前源码观察

- `apps/web/src/platform/providers.tsx` 的 `ConfigProvider` 已配置 `modal={{ centered: true }}`。
- Ant Design 6.6.3 的 `Modal` 组件通过 `useComponentConfig("modal")` 合并全局 `centered`；源码在 `apps/web/node_modules/antd/es/modal/Modal.js`。
- `App.useApp().modal.confirm()` 与 `Modal.confirm()` 是两种调用路径。当前代码中，确认框有 5 处使用 `App.useApp()` 的 `modal.confirm()`，另有 1 处直接使用静态 `Modal.confirm()`。静态方法不读取 React 上下文，不能只依赖 `ConfigProvider` 的默认值。
- 用户截图的“放弃未保存的更改？”来自 `features/users/user-editor.tsx` 中的 `App.useApp().modal.confirm()`。应用源码配置和截图表现不一致；当前浏览器停在登录路由，无法确定该页面运行时是否已加载这项全局配置。

## 建议的源码改动

保留 Providers 里的全局 `centered` 默认值，并加一个集中封装，令所有上下文确认框明确使用屏幕居中：

```tsx
"use client";

import { App } from "antd";
import { useCallback } from "react";

export function useCenteredModalConfirm() {
  const { modal } = App.useApp();
  return useCallback(
    (config: Parameters<typeof modal.confirm>[0]) =>
      modal.confirm({ ...config, centered: true }),
    [modal],
  );
}
```

新增文件建议为 `apps/web/src/platform/components/use-centered-modal-confirm.ts`。将下表列出的 5 处上下文 `modal.confirm()` 改为该 hook；`file-retention-page.tsx` 的静态 `Modal.confirm()` 保留现状，只显式加 `centered: true`。这样覆盖用户截图中的确认框，也覆盖当前其余所有确认框。

普通 `<Modal>` 继续使用 Providers 的全局配置，不逐个页面重复写 `centered`。建议在浏览器验收时确认每个对话框的 Ant Design wrap 获得 centered 状态；若某个 `<Modal>` 没继承到，再为该处单独加显式属性。

## 组件覆盖表

| 类型 | 当前调用点 | 建议处理 | 居中范围 |
|---|---|---|---|
| 用户编辑未保存确认、额度变更确认 | `features/users/user-editor.tsx`，2 处 | 改用 `useCenteredModalConfirm()` | 用户截图中的确认框；水平、垂直居中 |
| 角色额度变更确认 | `features/roles/roles-page.tsx`，1 处 | 改用共享 hook | 水平、垂直居中 |
| 敏感词导入确认 | `features/sensitive/sensitive-words-page.tsx`，1 处 | 改用共享 hook | 水平、垂直居中 |
| 个人资料离开确认 | `features/account/profile-drawer.tsx`，1 处 | 改用共享 hook | 水平、垂直居中 |
| 文件保留策略确认 | `features/settings/file-retention-page.tsx`，1 处静态方法 | 显式传入 `centered: true` | 水平、垂直居中 |
| 声明式 `<Modal>` | `events/outbox-page.tsx` 1 处；`localization-page.tsx` 3 处；`account/passkeys-drawer.tsx` 1 处；`sensitive-words-page.tsx` 1 处；`agreement-links.tsx` 1 处；`settings-section.tsx` 1 处 | 继续由 Providers 的全局默认值控制 | 水平、垂直居中 |
| Drawer / DrawerForm | 多处 | 不改 | 保留现有侧边抽屉布局 |
| Popconfirm / Popover / 菜单与下拉框 | `delete-action.tsx`、`draft-actions.tsx` 等 | 不改 | 保留靠近触发控件的锚点布局 |

当前共盘点出 6 个 imperative confirmation 调用和 8 个声明式 `<Modal>`。没有发现其他 `Modal.info/success/error/warning` 调用。

## 验收计划（待协调窗口）

在源码改动获准且可安全热更新后，选择一个普通 `<Modal>` 和用户编辑抽屉中的未保存确认框，读取各自面板 `getBoundingClientRect()` 与 `window.innerWidth/innerHeight`；两轴中心差应不超过 1 CSS px。再检查长内容弹窗仍可从顶部滚动访问、窄视口无水平溢出，并确认抽屉和 Popconfirm 的原有锚定行为没有改变。当前没有执行这些浏览器验收。

## 本次现场边界

只读清单中 Edge 的两个 Lumira 标签分别停在登录页（返回目标为用户管理、敏感词页），Codex 内置浏览器也停在登录页（返回目标为 AI 能力页）；未见模型编辑页 URL，但不能据此断言标签内没有隐藏抽屉或未保存输入。CSS viewport、DPR、缩放比例、`data-display-scale` 和 computed 字号均未知。为避免热更新丢失登录页中可能未提交的内容，本次未触碰应用源码、未刷新页面、未重启服务。
