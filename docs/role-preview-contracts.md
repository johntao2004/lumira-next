# 角色视角预览交换与实施边界

本文冻结 P10 前后端契约，遵守[统一设计](role-authentication.md)中的真实身份、权限与数据范围交集及写边界要求。能力在客户端、宿主、插件 SDK、SQL 和资源读取全部接入后才开启；单独新增菜单不是完成。

## 状态与 API

`/api/auth/me.authorizationContext`：

```ts
type AuthorizationContext = {
  revision: string | null;
  rolePreviewSupported: boolean;
  preview: {
    state: 'ACTIVE' | 'BLOCKED';
    roleId: string;
    roleName: string;
    roleVersion: number;
    startedAt: string;
    expiresAt: string;
    limitedByActor: boolean;
    blockedReason: string | null;
  } | null;
};
```

能力关闭时 revision/preview 为 null；开启时正常模式也具有不透明 revision。`authorities/globalAuthorities` 反映当前有效业务权限；真实 actor 及认证事实另行保留，不能把预览角色传给身份认证或内容安全策略。

`GET /api/account/role-preview/roles?page=1&pageSize=20`，返回现有 PageResult。条目 `{id, code, name, version, dataScope, assigned, limitedByActor}`。普通账号仅见本人启用且已分配角色；未分配角色须新增全局 roles:preview 和全局 roles:read。不能把角色列表读取权限默认扩展为模拟权限。

`GET /api/account/role-preview` 返回 AuthorizationContext。`POST /api/account/role-preview` 输入 `{roleId, roleVersion, authorizationContextRevision}`，返回新 AuthorizationContext。`DELETE /api/account/role-preview` 输入 `{authorizationContextRevision}`，返回新 AuthorizationContext。POST/DELETE 保留 CSRF。对过期、目标撤销、目标版本变化或 actor 权限撤销，服务端推进 revision 并转 BLOCKED；用户显式退出后再恢复本人正常模式。

## 请求、事务与资源

所有已认证业务 API 请求携带 `X-Lumira-Authorization-Context-Revision`，缺失/过时返回 409 `authorizationContextChanged`；BLOCKED 返回 403 `rolePreviewBlocked`。`/auth/me`、CSRF、预览状态/控制与退出登录是受控例外。每次切换后的旧请求只能刷新状态并由用户重新操作，不能自动重试旧写入。

状态存在共享数据库，以真实会话绑定，不能只存 JVM Map/HTTP session 的可能过期快照。模式切换与业务写使用兼容的每会话数据库锁或 CAS；检查必须在实际事务/副作用边界生效。不同用户不使用一个全局排他锁；供应商网络调用不持有数据库长锁。排队中的旧修订写请求，在进入写边界时必须被拒绝。

读取仅放行明确的无副作用路径/方法。未知端点与插件读取默认拒绝；账号本人基线读取显式列出，GET 不等于无副作用。文件导出、通知已读、AI工具执行、上传与任何业务写均拒绝。宿主与插件安全读取声明统一校验，审计始终记录真实操作者和预览目标。

SQL 不使用连接级 SET 或 ThreadLocal 隐式改变数据库身份。查询显式传 actor、当前有效权限和目标角色上下文，在原始可见范围基础上再 AND 目标范围；AdministrationGuard、用户/组织查询、角色列表、导航、通知与反馈插件同时检查。SELF 始终绑定真实账号，不能把管理员自身预览等同于普通账号隔离证明。

原生资源通过 `POST /api/account/role-preview/resource-tickets` 申请短时票据，输入 `{path, authorizationContextRevision}`；只能为显式白名单的无副作用同源资源路径签发，返回 `{url, expiresAt}`。不得使用绝对 URL、任意请求方法或下载/导出路径。资源读取必须核对当前会话、票据 revision 与服务器最新 revision，并重查当前读取范围。正常模式也检查修订，不能通过退出预览恢复旧资源票据。

## 界面与首次授予

角色列表提供“只读预览”；普通账号安全入口提供本人角色选择。始终显示角色、真实操作者、10分钟到期和退出按钮；BLOCKED 明确提示重新选择或退出，不能自动退出。进入/退出通过 BroadcastChannel 提醒其它标签重新读取 session、导航、列表及权限缓存，安全性依靠服务器 revision。

新增权限 `platform:roles:preview` 纳入权限目录与全新数据库 bootstrap 管理员。已有部署需要定向、带原因和审计的引导命令，仅授予该一个权限给拥有全局 roles:write 与 settings:write 的指定管理账号；不自动赋予所有角色读用户。未执行首次授予时管理员可继续查看本人已分配角色。

验收必须包含直接 URL/API、两普通账号所有权隔离、SELF 与组织范围交集、两标签进入/退出、过期/撤销 BLOCKED、旧 revision 的写请求和资源票据、多实例共享上下文，以及未知插件端点拒绝。生产和开发热更新的插件运行行为分别记录。
