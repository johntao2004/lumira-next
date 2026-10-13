# 二次认证与验证器

`mfa` 是 Lumira 的 TOTP 验证器插件，依赖 identity 核心模块。它提供账号自助绑定、恢复码、登录挑战和受权限保护的管理员重置。插件不发送短信、邮件或微信验证码。

接口、存储、安全状态与验收边界见 [二次认证与验证器插件](../../docs/mfa.md)。插件包按共享约定构建：

```sh
pnpm plugins:typecheck
pnpm plugins:build mfa
```

安装包位于 `plugins/mfa/dist/mfa-<version>.lumira-plugin.zip`。通过平台的插件管理导入和安装；请勿手动改写 `.runtime/plugins/` 中的已安装制品。

插件清单将 `/api/mfa/account` 自助接口声明为 `authenticatedOnly`，使任一登录用户能管理自己的因子，无需获得管理权限。管理页面使用 `platform:mfa:management-access`，全局查询还需要 `platform:mfa:management-read`；管理员重置还需要 `platform:mfa:management-reset` 和 `platform:users:update` 对目标用户的可见范围。首次授权时须在角色管理中分别配置页面和操作权限，安装插件不会自动授予这些新权限。插件 `1.0.9` 的 V7 最终化迁移保留旧 `mfa:read`/`mfa:manage` 已有的页+读来源，并要求旧 `mfa:reset` 同时具备 V50 全局重置标记和目标范围来源，才转换成新的重置操作。先将宿主升级到 V51，再升级或安装此版本；若 V6 在新安装时仍待执行，宿主会记录其原始脚本校验和但跳过旧引导 SQL，因此不会自动授予新管理页或操作权限。两个管理操作仍受重新认证和审计约束。identity 核心保留账号的二次认证要求，因此插件暂时不可用时已启用 MFA 的账号会被拒绝登录，不会自动回退到密码认证。
