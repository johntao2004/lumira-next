# 本轮目录整理与兼容保留

## 已替代并移除的旧位置

- `plugins/sdk/backend` 的 Java 公共契约源码移至 `framework/plugin-sdk` 与 `framework/payment-sdk`；保留公开类名和协议版本，插件脚本使用独立 Maven 依赖，不再读取宿主 `target/classes`。
- 宿主内可独立使用的公开契约移至 `framework/platform-contracts`，宿主引用该版本化构件。内部存储、配置、权限执行与运行时实现仍留在宿主，避免把实现误当开发 API。
- `docs/cross-machine-testing.md` 的临时分支交接说明已删除；仍适用的开发和测试说明并入 [cloud-development.md](cloud-development.md)。README 和其他入口同步更新。

上述删除可由 Git 恢复；不清理提交历史或用户数据。

## 有意保留

- 历史数据库迁移及校验和、旧文件存储读取兼容、公开 API/FQN 和插件注册 ABI。
- 全部现有正式插件及 SDK 示例；没有按路线图优先级删除功能。
- 安全、许可证、备份恢复、运维和故障排查文档。
- 当前仍被动态装配、反射、插件资源或构建脚本使用的实现。仅搜索不到静态引用不能证明可删除。

未来清理必须同时检查静态引用、运行时注册、资源路径、构建入口和文档链接，并提供对应回归结果。

## 2026-10-19 个人资料旧抽屉清理

删除 `apps/web/src/features/account/profile-values-drawer.tsx`：全仓库无 import、路由或动态注册引用，实际个人资料入口由 `account/module.tsx` 挂载 `profile-drawer.tsx` 的整合表单。核心清单迁到实际入口；保留资料 API、字段模型、SQL 和自定义资料功能。新的固定快照与重载保护直接用于实际入口，不再保留未使用的平行实现。
