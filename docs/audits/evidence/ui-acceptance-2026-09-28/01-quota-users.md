# 窗口①：用户、角色、组织与文件配额

状态：进行中。以下 source-support 只用于定位权限和后续 UI 步骤，不构成 UI 通过证据。

## Source-support：个人文件页最小权限

`/dashboard/files` 在 `apps/web/src/features/files/manifest.ts:3-10` 注册为必需模块中的本人文件页，路由 `permission: null`。`apps/web/src/features/files/files-page.tsx:21-41` 通过 `/account/files` 调用本人列表、上传、下载和删除控件，没有检查任何 platform authority。

| UI 操作 | 后端端点 | 源码确认的权限与数据范围 |
| --- | --- | --- |
| 用量、列表 | `GET /api/account/files/usage`、`GET /api/account/files` | 登录会话即可；`SecurityConfiguration.java:77-79` 只要求 `authenticated()`。`FileController.java:22-24` 以当前 `Authentication.getName()` 调用服务；`FileService.java:141-143,177` 和 `FileRepository.java:98-105` 按当前账号 owner 查询。 |
| 上传 | `POST /api/account/files?filename=…` | 登录会话即可，无 role authority。`FileController.java:25-31` 另执行上传速率限制和 20 MiB 单文件上限；`FileService.java:97-104` 用当前账号作为 owner，并在保存前检查其有效配额。 |
| 下载 | `GET /api/account/files/{id}/content` | 登录会话即可；`FileController.java:34-39` 先经 `FileService.find` 查当前账号所有的文件，再流式读取。`FileService.java:132-134` 使用 `findOwned`，其他账号文件不会因知道 ID 而可见。 |
| 删除 | `DELETE /api/account/files/{id}?version=…` | 登录会话即可，无 `platform:files:write` 要求。`FileService.java:206-210` 先以当前账号查文件并校验 version；`FileRepository.java:141-145` 还校验 owner、READY 状态及头像/反馈引用保护。 |

结论：登录后的唯一 QA 普通用户可用零额外权限（`authorities=[]`）验证本人文件页面的上传、列表、下载和删除；无需也不应给该用户 `platform:files:read` 或 `platform:files:write`。删除仍受 owner、版本及被引用文件保护约束；上传仍受配额、速率和单文件大小限制。源码合同不代替下一阶段的真实 UI 操作及刷新后验证。

## Source-support：全局文件权限注册及角色 UI 标签

全局文件中心是另一套路径：`/dashboard/files-all` 路由标记 `permission: "platform:files:read"` 且 `global: true`（`manifest.ts:10`）；其 UI 只有在 `hasGlobal("platform:files:write")` 时才允许写（`apps/web/src/features/files/module.tsx:10-12`）。后端 `/api/platform/files` 的读取要求 `platform:files:read`，删除要求 `platform:files:write`（`SecurityConfiguration.java:72-73`）；`FileService.java:146-160` 再要求全局范围。这两项均属于全局文件中心，不属于本人文件 CRUD。

权限 registry 是 `RoleService.PERMISSIONS`（`backend/src/main/java/com/lumira/platform/roles/RoleService.java:12`），`RoleController.java:17` 由 `/api/platform/permissions` 返回。角色编辑器按 `platform:files` 前缀将它们放进“全部文件”组；`:read` 映射为“页面访问”，`:write` 映射为“管理”（`apps/web/src/features/roles/roles-page.tsx:36-42,70-85`；简体中文标签见 `apps/web/src/platform/i18n/messages.ts:384-385`）。这是权限标签对应关系，不代表本人文件页需要这两个权限。

配额只角色可把 authority 集合保持为空：`RoleService.RoleInput` 独立保存 `authorities` 和 quota 字段（`RoleService.java:13-14`），`RoleQuotaPolicyAdapter.java:41-51` 对各维度分别取启用角色的最小限额，并在该维度无启用限额时回退系统默认值；`RoleRepository.java:51-60` 仅返回启用角色的 quota 记录。因此可以用一个 `SELF`、`authorities=[]` 的测试角色承载配额，不扩大页面访问权限。实际创建/分配尚未进行，须待共享 UI 窗口空闲后通过 UI 操作。

## Admin 用户编辑抽屉：失败路径预检

### UI 观察（独立于 source-support）

此前打开 `/dashboard/users` 中 admin 行的编辑抽屉时，页面显示“服务暂时不可用，请稍后重试”；点一次重试后内容仍空，保存按钮 disabled。随后关闭抽屉，没有提交保存，也没有修改 admin。新建用户空抽屉只有用户名、名称和密码字段；该表单随后取消，未创建账号。以上是 UI 观察，不代表已定位失败请求。

本轮再次通过真实 UI 打开 admin 编辑抽屉：点击 Retry 前 **2026-09-28 16:53:05 UTC / 2026-09-29 00:53:05 Asia/Shanghai**；等待 3 秒后于 **2026-09-28 16:53:23 UTC / 2026-09-29 00:53:23 Asia/Shanghai** 检查，终态仍为“服务暂时不可用，请稍后重试”，抽屉主体空、保存 disabled。关闭抽屉前未提交任何更改。更早一次 Retry 的绝对时间戳没有保留，只能确认等待约 2.5 秒后仍为同一终态。

后端快照修复并重启后，通过真实 Edge 新标签再次打开 `/dashboard/users` 的 admin 编辑抽屉。**2026-09-29 01:08:01 Asia/Shanghai**（**2026-09-28 17:08:01 UTC**）等待 3 秒后，基本资料已显示：名称“管理员”、启用状态、只读用户名；角色与组织标签也可见。保存按钮 disabled；没有编辑或保存 admin。视口 **2390×1062**。列表仍是原有 4 个用户。此项是 UI 前后变化证据；角色/组织快照数据及 QA 用户流程仍在进行。

### 源码映射

`apps/web/src/features/users/user-editor.tsx:80-85` 在编辑现有用户时首先读取 `GET /platform/users/{id}`；`298-302` 在该 detail query 出错时显示 `QueryFailure(unavailable)`，无 detail data 时不渲染表单；`150-152` 因无 detail data 或有 detail error 而禁用保存。`QueryFailure` 展示传入标题并提供重试按钮（`apps/web/src/platform/components/query-failure.tsx:6-23`）；`unavailable` 的简体中文文案就是“服务暂时不可用，请稍后重试”（`messages.ts:688`）。因此 UI 结果与主 detail 查询失败分支相符，但该通用文案不包含 HTTP 状态或后端异常。

主读取映射为 `UserController.find`（`backend/src/main/java/com/lumira/platform/users/UserController.java:22-26`）→ `UserService.find`；后者要求 `platform:users:read` 和相应用户范围（`UserService.java:62-66`）。drawer 的角色、组织、角色列表与 quota preview 是另行查询并在各自 tab 显示状态（`user-editor.tsx:95-129,309-313`）。保存才会走 `PUT /api/platform/users/{id}/editor` → `UserEditorService.save`（`UserController.java:31-35`；`UserEditorService.java:29-52`）。前次抽屉没有提交，所以 `UserEditorService` 不是已观察失败的调用路径。

### 日志与结论

`scripts/start.mjs:57-70` 将 backend 的 stdout/stderr 重定向到 `.runtime/backend.log`。本次只检查了日志文件元数据：最后修改时间为 **2026-09-28 20:41:28（Asia/Shanghai）**、大小 5,017 bytes；远早于该 UI 操作窗口。未读取该旧日志内容，也没有近期匹配该编辑 drawer 请求的 backend stack 可供归因。没有浏览器 Network/Console 请求状态记录。

现有证据确认两次 UI Retry 后的稳定错误终态，但不能确认具体失败 URL、状态码或根因，尚不足以判定服务缺陷。本轮按 F12 未打开可用 DevTools 面板；受限 `playwright.evaluate` 上下文中的 `performance` 未定义，因此没有可用 Network path/status/timing 证据。没有查看请求头、正文或凭据，也没有直接发起请求。后续若 QA 用户编辑抽屉可用，再只读观察其真实 UI 状态；不能把该 admin 错误归因到保存服务或后端异常。

## 当前窗口进度与凭据处理

新增用户抽屉的可见字段只有必填用户名、名称、密码，没有默认角色或 authority 控件；本轮最终将该抽屉留空打开，没有创建用户。一次临时填写的用户名/名称草稿在提交前已撤销，列表刷新仍为原有 4 个用户，没有新账号持久化。

凭据桥接仅用于安全流程演练：旧 helper 按 600 秒上限退出，`exit_code=0`，新增输出长度 18 字节，符合其通用 `RESTORED_TIMEOUT` 回执长度（CRLF），协调者据既知 helper 合同确认自动恢复；没有把回执正文或剪贴板内容写入证据。随后启动的独立 stdin helper 返回 `READY`，但在密码粘贴前即发送 `RESTORE`；其成功出口 `exit_code=0`，按控制逻辑完成 `RESTORED`，没有密码进入表单或被提交。未创建 QA 用户，未提交任何角色或权限。

当前窗口停在空白新增用户抽屉，等待管理员编辑详情查询的修复后复测；admin 未更改，QA 创建和其余配额/文件/资料字段 UI 测试尚未执行。

## 修复后管理员详情复测

服务重启后旧 Edge 标签 ID 已不存在，因此在同一 Edge Profile 1 中重新打开 `http://127.0.0.1:8010/dashboard/users`。新标签 viewport 为 **2390×1062**。于 **2026-09-28 17:08:01 UTC / 2026-09-29 01:08:01 Asia/Shanghai** 等待 3 秒后，admin 编辑抽屉的“基本资料”真实加载：用户名只读、名称“管理员”、状态启用，角色和组织标签可见，保存按钮 disabled。没有编辑或保存 admin。用户列表仍为 4 条既有用户；后续创建 QA 之前没有新增账户。

## Source-support：用户详情只读锁冲突与配额/文件调用链

### 抽屉读取链

编辑抽屉在 `apps/web/src/features/users/user-editor.tsx:80-85` 读取 `GET /platform/users/{id}`，且在 `:95-129` 分别读取角色分配、组织分配、角色/组织列表，以及角色选择驱动的 `GET /platform/users/{id}/file-quota/preview`。详情重试入口位于 `:298`；角色与组织快照各自有独立错误和重试分支（`:309-313,331-334`）。抽屉保存走另一条 `PUT /platform/users/{id}/editor` 路径（`:243-280`），由 `UserController.java:31-35` 进入 `UserEditorService.save`。

详情 GET 的后端链为 `UserController.find`（`backend/src/main/java/com/lumira/platform/users/UserController.java:22-26`）→ `UserService.find`（`UserService.java:62-67`）→ 原来的锁定查询 `UserRepository.find`（`UserRepository.java:51-55`）。`UserService.find` 明确处于 `@Transactional(readOnly=true, isolation=REPEATABLE_READ)`；原 repository SQL 却带 `FOR UPDATE`。当前异常处理器把 `DataAccessException` / `TransactionException` 转为 HTTP 500 `unavailable`（`backend/src/main/java/com/lumira/platform/common/ApiExceptionHandler.java:18-25`），与抽屉通用 unavailable 分支相容。由于本次浏览器 Retry 没有捕获实际 Network path/status，且对应日志时间窗没有异常行，PostgreSQL 复现确认了同类 SQL 的失败行为，但不能直接归因那次真实 HTTP 请求。

### 配额、角色和组织快照

角色分配快照由 `RoleController.userRoles`（`backend/src/main/java/com/lumira/platform/roles/RoleController.java:23-24`）调用 `RoleService.userRoles`（`RoleService.java:66-70`）；组织分配快照由 `MembershipController`（`backend/src/main/java/com/lumira/platform/organizations/MembershipController.java:9-14`）调用 `OrganizationService.memberships`（`OrganizationService.java:59-63`）。修复前这两个 GET 快照使用默认可写 `@Transactional`，通过 `UserRepository.version` 的 `SELECT ... FOR UPDATE`（当前仍在 `UserRepository.java:62-66`）读取版本后，再读分配项。修复后它们使用只读 `REPEATABLE_READ`，并经 `UserDirectoryPort.versionSnapshot` / `UserRepository.versionSnapshot`（`UserDirectoryPort.java:9-10`、`UserRepository.java:68-72`）无锁读取版本。写入 `RoleService.assign` 和 `OrganizationService.assign` 仍调用锁定版 `version`，并在事务内校验版本及更新关联。角色配额预览由 `UserFileQuotaController` 暴露（`backend/src/main/java/com/lumira/platform/files/UserFileQuotaController.java:24-34`），前端抽屉当前调用 `/preview`。`FileService.previewUserQuota` 处于只读 `REPEATABLE_READ`（`FileService.java:184-192`），通过 `quotaOwner(userId,false)`、文件用量和有效角色限额组成同一预览快照；这里传 `false`，不会对用户行加锁。当前额度通过启用角色限额按维度取最小值，无限额时回退默认值（`RoleQuotaPolicyAdapter.java:23-51`）。个人文件页的用量组件调用 `/account/files/usage`（`apps/web/src/features/files/file-usage.tsx:9-20`；后端 `FileController.java:22`），服务同样处于只读 `REPEATABLE_READ`，按当前账号读取用量与有效额度（`FileService.java:176-177`）。管理端用户额度 GET 也使用只读事务与 `quotaOwner(...,false)`（`FileService.java:178-183`）。

### 需保留的写入并发锁

- `UserRepository.find` 仍供 `UserService.create/update/delete` 使用；其中更新和软删除先取锁、校验版本，再修改用户资料/状态并撤销会话（`UserService.java:32-60,69-80`）。详情 GET 已改用独立无锁 `findSnapshot`（`UserRepository.java:56-60`），没有把写路径改为无锁。
- 上传预留通过 `FileService` 的 `TransactionTemplate` 写事务执行（`FileService.java:92-105`）：锁当前账号和 `quotaOwner(ownerId,true)` 用户行，读现有用量、校验有效额度并插入 reservation。`FileRepository.usage` 将已完成大小或未完成预留大小计入用量并计文件数（`FileRepository.java:37-43`）。这个用户行锁使同一用户的文件预留互相串行，并与同一用户的角色赋值版本锁协调；预留提交后才开始流式存储。
- 发布上传时另开写事务，锁当前账号及 UPLOADING 文件行，校验状态/版本/过期时间，再发布并标记 READY（`FileService.java:106-120`；`FileRepository.java:60-64,129-139`）。该文件行锁是发布围栏。
- 用户删除与全局删除在事务中锁定文件行，再按版本、READY 状态和头像/反馈引用条件转为 DELETING；物理 blob 删除在事务外执行，之后用新事务完成元数据删除（`FileService.java:158-169,206-217`；`FileRepository.java:123-145,155-158`）。不能移除这些引用/状态保护锁。头像上传/删除还用 `AvatarRepository.find(...,true)` 锁媒体关联行（`AvatarRepository.java:13-17`；`AvatarService.java:39-69`）。
- 清理任务用 `FOR UPDATE SKIP LOCKED` 分批领取过期上传和孤儿文件，然后在锁外删存储对象（`FileRepository.java:160-170`；`FileCleanup.java:20-34`）。PostgreSQL blob 分块写入先锁元数据行以校验状态和版本（`PostgresBlobStore.java:156-173`）；定时回收退役 chunk 也通过单条 CTE DELETE 的 `FOR UPDATE SKIP LOCKED` 避免并发重复领取（`:276-287`）。

### 受控只读数据库复现与证据边界

为验证 PostgreSQL 行为，在本机 PostgreSQL 连接中取消 `PGPASSWORD`，设置 `PGPASSFILE=/dev/null`，执行只读事务。查询只投影 `id`，结果不输出或保存；没有读取用户名、密钥或 credential，也没有写入业务数据：

```sql
BEGIN READ ONLY;
SHOW transaction_read_only;
SELECT id FROM platform_user WHERE deleted_at IS NULL LIMIT 1 FOR UPDATE;
ROLLBACK;
```

脱敏观察结果：`transaction_read_only=on`；锁定查询报 SQLSTATE `25006`（read-only transaction 中不允许该 `SELECT FOR UPDATE`）；随后 `ROLLBACK` 标签已确认。psql 进程最终退出码为 0，因为语句错误后继续执行了显式回滚。该实验验证了源码中的同类 SQL 在 PostgreSQL 只读事务下会失败；它不证明浏览器当次请求实际命中了该 SQL，也不替代真实 UI / Network 验收。

前序对 `.runtime/backend.log` 的筛查在 2026-09-28 16:53:00–16:53:30 UTC 时间窗得到 0 行；本小节不重复扫描。该时间窗没有可关联异常行。浏览器侧当次 Retry 没有 path、status 或 timing，因此仍不能声称已取得请求级日志证据。

### 修复实施与验证状态

已在 `UserRepository` 新增无锁 `findSnapshot`，投影及未软删除条件与锁定版 `find` 相同；仅 `UserService.find` 在既有只读 `REPEATABLE_READ` 边界内调用快照查询。另新增 `versionSnapshot` 契约并将角色/组织分配 GET 改为只读 `REPEATABLE_READ` 快照。锁定版 `find` 仍用于 create/update/delete；锁定版 `version` 仍用于角色/组织赋值的版本串行化。文件上传预留、发布、删除、头像关联和清理任务锁均未改。没有将用户详情 GET 改成可写事务，也没有全局移除锁。

后端已用 Node `v24.21.0` 环境下的 Maven Wrapper、JDK 25 执行 `./backend/mvnw -f backend/pom.xml -DskipTests package`，结果 `BUILD SUCCESS`；Surefire 显示测试跳过。随后使用 `node scripts/stop.mjs` 和 `node scripts/start.mjs` 做了一次受控更新，启动脚本报告 scaffold ready；`node scripts/status.mjs` 对 backend readiness 和 frontend login 均检查为 HTTP 200。以上是源码、编译/打包及本地健康证据，不替代浏览器验收。修复后 admin 基本资料抽屉已在 UI 中加载成功并保持未修改；QA 编辑器、角色/组织数据和 quota preview 仍待验证。

修复后另在本地 PostgreSQL 只读可重复读事务中执行新详情投影及新版本快照的查询形状，使用 `env -u PGPASSWORD`、`PGPASSFILE=/dev/null`；仅脚本输出状态，所有行值均被抑制。`transaction_read_only=on`，两个无锁 SELECT 成功且无 SQLSTATE，最后显式 `ROLLBACK` 确认、退出码 0。这验证了新读取可运行在只读事务，不是后端 HTTP/授权测试。源码回读确认详情 `platform:users:read` 与用户范围 guard、角色/组织各自的用户范围 guard 保留；GET 快照只使用无锁查询，role/org assign 与 user create/update/delete 仍使用锁定方法。配额预留、上传发布、文件删除及引用保护、头像关联与 cleanup `SKIP LOCKED` 的原有锁仍在源码路径，未做上传并发集成测试。

## QA 凭据粘贴能力失败与暂停状态

修复后新增用户表单只填入预定的合成用户名和显示名，未保存。浏览器 locator 的 `Meta+V` 返回 `Browser Use virtual clipboard has no data`，因此该次粘贴没有成功。随后尝试通过 Edge 原生应用键盘粘贴时，应用当前选中标签实际为 `openai.com/zh-Hans-CN/live/`，焦点在 Vimeo HTML 内容，而非 Lumira 标签；无法排除页面收到 paste 事件。将该次临时生成的 QA 凭据作废，不再用于账号；没有在本证据中保存凭据值，也没有提交用户表单。

同一 clipboard helper 收到 `RESTORE` 后返回通用 `RESTORED`，进程退出码 0，原剪贴板已恢复。当前 UI 表单未保存，用户名/名称草稿仍在表单中，密码未被确认进入 Lumira 密码框；账户列表仍只有原有 4 个用户，未创建 QA 账号、角色或权限。依据本轮 CUA 浏览器使用规则中的“新凭据由用户亲自输入、确认和提交”交接要求，已请用户在 Lumira `/dashboard/users` 完成 QA 密码输入、保存并登录到文件页，随后再继续 UI 验证。不声称此项 UI 测试已完成。

## 最新用户凭据交接准备（待用户完成）

按用户要求另建独立可见的 Edge 标签，Profile `用户配置 1`，创建时 `sessionName` 为 `🧪 QA Credential Handoff`，tab ID **1021204172**，URL `http://127.0.0.1:8010/dashboard/users`，标题“用户列表 - Lumira”；已调用该 tab 的 `markHandoff()`。同 Profile 的新标签沿用管理员会话，页面显示用户列表的 4 条既有用户。

通过页面新增用户抽屉只填入非秘密字段：用户名 `qa_quota_ui_202609281638519dn7`，名称 `Quota UI QA 202609281638519dn7`。CUA 截图目视确认密码框为空、无掩码点；AX 只显示空密码 textbox，未读任何密码值。没有生成或粘贴密码，没有点击“保存”，抽屉仍未提交；没有创建账号或角色。此轮交接准备没有重复前述误将原生粘贴发到 OpenAI/Vimeo 标签的事件，亦未启动 clipboard helper。

**唯一待用户操作：**在该保留标签上亲自输入一个新的临时 QA 密码并点“保存”，随后使用此 QA 账号完成 Lumira 登录；只通知协调者“QA 用户已保存并登录（密码未发送）”，不要发送密码。收到此非秘密完成信号后，再继续角色、配额、文件和资料字段 UI 验收。

## Source-only preflight：角色配额表单与组织层级的精确清理能力

本节仅记录只读源码映射，不计 UI 验收通过；本轮没有打开或操作浏览器，也没有调用 API 或数据库。

### 角色与 SELF 配额

- 新建角色抽屉的前端初值为 `enabled=true`、`authorities=[]`、`dataScope=SELF`，两项额度均为空；表单可输入角色代码、名称、启用状态、数据范围、GiB 存储额度和文件数额度，并展示多角色逐维最小值与系统默认回退说明（`apps/web/src/features/roles/roles-page.tsx:109-112,146-160`）。保存会通过 UI 的提交处理器 POST `/platform/roles`，编辑使用 PUT `/platform/roles/{id}`；额度变化/启用状态变化需先确认影响说明，再使角色、session 和 quota preview 等查询失效并刷新（`:123-143`）。
- 表格删除是确认弹窗后调用 DELETE `/platform/roles/{id}?version=...`（`apps/web/src/features/roles/roles-page.tsx:103-108`）。后端 `RoleService.delete` 校验版本、现有权限以及没有任何用户分配；随后 `RoleRepository.delete` 执行 SQL `DELETE FROM platform_role`（`backend/src/main/java/com/lumira/platform/roles/RoleService.java:58-65`、`RoleRepository.java:70`）。`RoleController` 仅提供 GET/POST/PUT/DELETE 角色路由，没有 restore 路由（`RoleController.java:14-22`）。在源码中未找到角色软删除字段、停用后恢复删除记录或角色 restore API；停用只是可逆 enabled 状态，不能精确清除已创建记录。
- 结论：虽然空 authority 的 SELF 配额表单可以安全表达目标角色，但精确删除是硬删除且没有 UI 恢复路径。根据“无精确可恢复清理就不创建”的约束，本轮不创建合成角色；表单的真实 UI 展示、保存/刷新、启停仍未测。

### 组织父子层级

- 组织表提供创建按钮、逐行编辑和逐行 DeleteAction；组织表从分页数据构建树视图。抽屉表单包含不可编辑的代码（编辑时）、名称、父组织选择器和 enabled 开关；提交通过 UI POST `/platform/organizations` 创建或 PUT `/platform/organizations/{id}` 编辑，然后刷新列表（`apps/web/src/features/organizations/organizations-page.tsx:155-200`、`organization-form.tsx:7-20`）。
- UI 删除确认后调用 DELETE `/platform/organizations/{id}?version=...`（`organizations-page.tsx:176-181`）。服务先校验版本，再检查组织是否存在子组织或用户关联；若有则拒绝并返回 `organizationInUse`，其中文案为“组织下仍有子组织或用户，请先解除关联”。通过检查后 `OrganizationRepository.delete` 执行 SQL `DELETE FROM platform_organization`（`backend/src/main/java/com/lumira/platform/organizations/OrganizationService.java:52-57`、`OrganizationRepository.java:64-67`；文案 `apps/web/src/platform/i18n/messages.ts:460`）。`OrganizationController` 仅提供 list/find/ancestors/create/update/delete，没有 restore 路由（`OrganizationController.java:15-22`）。源码中未发现组织软删除字段或恢复 UI；父组织记录还受 `ON DELETE RESTRICT` 外键保护（`backend/src/main/resources/db/migration/V1__platform_initialization.sql:1850-1854`）。
- 结论：空父/子组织虽可依序删除，但删除链不可恢复，子组织/成员引用也会阻止父项删除。按精确清理约束，本轮不创建合成组织；组织表单/树交互/编辑刷新/删除保护没有真实 UI 验收证据。

### 证据边界与下一步

以上证明只是从 UI 源码和服务/仓储调用链判断：当前 UI 删除动作最终调用硬删除仓储，没有发现任何角色或组织 restore/软删除工作流。没有通过页面实际查看或确认 Popconfirm 文案、表单排版或控件状态，因此这不能替代真实 UI 证据。若产品提供可恢复的删除/归档工作流后，可重新安排纯合成记录的创建与完整恢复验证；在此之前不创建这两类记录。QA 用户凭据交接表与既有 4 用户基线不受本次源检查影响。

## 2026-09-29 追加：获授权后的真实角色、组织及管理员个人文件 UI

本节记录稍后的显式授权和 Edge 窗口释放后执行的真实 UI 操作，取代上文“因硬删除路径而不创建”的当时结论。角色和组织记录通过产品 UI 创建/编辑；没有尝试硬删除。随后按协调者最新授权在管理员本人文件页上传并下载一个合成 fixture。UI 操作未使用直接 API/DB 写入或前端状态注入。只读数据库查询仅用于补齐 UI 不展示的 UUID 和关联计数，并在下方单独标明。

### 环境与路由

- 浏览器：Edge，Profile `用户配置 1`；管理员现有会话；UI 操作标签 **1021204180**。QA 凭据交接标签 **1021204172** 未触碰。
- 角色 UI：`/dashboard/roles`；组织 UI：`/dashboard/organizations`；个人文件 UI：`/dashboard/files`。文件闭环最后刷新后的视口约 **2390×1062**。
- 没有修改管理员的账号、资料、角色或配额；没有输入/生成凭据，没有创建或分配普通 QA 用户。

### SELF 零权限配额角色：真实 UI 操作

- 初始刷新后的角色列表有 2 条既有记录；都不含本轮标记。通过新增表单创建 `QA_SELF_QUOTA_20260928_181851`，名称 `Quota SELF UI 20260928 181851`，数据范围 `SELF`，所有权限复选框均未选，额度为 10.24 MiB / 3 文件。
- 通过编辑抽屉将名称改为 `Quota SELF UI EDITED 20260928 181851`，额度改为 0.02 GiB / 4 文件；确认额度变更提示后保存。刷新列表显示编辑后名称、20.48 MiB、4，证明 UI 保存并重新读取。
- 通过 UI 将状态改为停用并确认，刷新后显示“停用”；随后通过 UI 重新启用并确认，刷新后显示“正常”。最终角色处于启用状态。全程未分配给任何用户，也未授予 authority。

### 父子组织：真实 UI 操作

- 初始刷新后组织列表有 4 条既有记录，无本轮 marker。通过 UI 创建启用的顶层父项：代码 `QA_ORG_20260928_181851_P`、名称 `Quota QA Org Parent 20260928 181851`；再创建其子项：代码 `QA_ORG_20260928_181851_C`、名称 `Quota QA Org Child 20260928 181851`，父组织在表单下拉框中选择刚建的父项。
- 通过 UI 编辑子项名称为 `Quota QA Org Child Edited 20260928 181851`。刷新后展开父行，树中显示编辑后的子项及其父子缩进关系。
- 两个组织均未添加成员。没有删除、移动、禁用既有组织或触碰旧业务记录。

### UI 未展示的精确记录与关联（只读数据库补充）

以下 UUID/计数来自 `lumira_next` 的定向只读事务查询，不是 UI 通过证据；查询只选本节精确 marker 的 ID、状态、额度、关联计数，并在事务末显式 `ROLLBACK`。

| 项目 | UUID | 已持久化状态 / 关联 |
|---|---|---|
| SELF 配额角色 `QA_SELF_QUOTA_20260928_181851` | `b371fb27-1ca5-461b-b25f-618b484ebb55` | 名称 `Quota SELF UI EDITED 20260928 181851`；`SELF`；enabled=true；storage 21,474,836 bytes；4 files；authority count=0；user assignment count=0 |
| 父组织 `QA_ORG_20260928_181851_P` | `79953086-e3b5-451c-8a72-993ccda8b878` | parent=null；enabled=true；membership count=0；唯一子组织数=1 |
| 子组织 `QA_ORG_20260928_181851_C` | `99f904ff-1f26-4e1e-ae02-d5a39023e6b0` | 名称 `Quota QA Org Child Edited 20260928 181851`；parent UUID 与上列相同；enabled=true；membership count=0 |

### 管理员本人文件：上传、刷新及下载

- 上传前 `/dashboard/files` 的文件筛选为空，状态筛选为“请选择”；页面用量为 **20 B / 1 GiB、1 / 1,000**。文件表可见一条既有记录 `retention-policy-1790620230082.txt`，20 bytes，状态“删除待重试”，时间 2026-09-29 02:30。该记录从未被点击、打开、下载、恢复或删除。
- 通过页面可见的“上传文件”按钮触发 filechooser，并由浏览器 filechooser 控件选择本地合成 fixture `/tmp/lumira-quota-ui-20260928-181851.txt`。上传完成 UI 显示 `lumira-quota-ui-20260928-181851.txt`、126 bytes、“可用”，用量显示 **126 B / 1 GiB、1 / 1,000**。该记录是可见页面操作加 chooser 事件的证据，不声称通过 macOS 原生 Open 面板。
- 清空筛选后刷新整个页面，UI 仍显示这一个可用 126-byte 新文件和相同用量；再按旧名 `retention-policy-1790620230082.txt` 精确筛选，结果为“暂无数据”。恢复空筛选后重新加载，旧记录仍未出现在列表中。上传前的 20 B/1 条与上传后 126 B/1 条并非累加关系；可能涉及旧待清理记录的后台重试或其它列表/usage 更新时序，原因未证实。执行者没有触碰旧条目，不能将其消失归因为执行者删除，也没有尝试重建或恢复它。
- 只通过新文件所在行的可见“下载”链接下载：UI 链接路径中的 file ID 为 `c85825d9-9901-4e79-8938-f85e54deb766`。下载到 `/Users/johntao/Downloads/lumira-quota-ui-20260928-181851.txt`。源 fixture 与下载件均为 **126 bytes**，SHA-256 均为 `8ebf247c4dc2eec8ca3ce88a5c7f758292b134bc24ca4e5635e349cfe5b6e4c9`。无直接 API 下载或打开旧文件。
- 最终 CUA 页面快照显示筛选为空、1 条新文件、126 B/1 GiB 与 1/1,000；屏幕截图通过 CUA 作为本轮内联观测捕获，未另存独立截图文件。

### 精确清理与未覆盖项

- 以下本轮对象仍留在本地环境，**未执行删除**：role `b371fb27-1ca5-461b-b25f-618b484ebb55`；parent org `79953086-e3b5-451c-8a72-993ccda8b878`；child org `99f904ff-1f26-4e1e-ae02-d5a39023e6b0`；新个人文件 `c85825d9-9901-4e79-8938-f85e54deb766`；128 KiB 边界文件 `lumira-quota-chunk-boundary-20260929.txt`（UUID `d956b884-ddd4-43c7-8c7d-fc4439738553`）。本地 fixture `.runtime/quota-chunk-boundary/lumira-quota-chunk-boundary-20260929.txt` 与下载副本 `/Users/johntao/Downloads/lumira-quota-chunk-boundary-20260929.txt` 也保留。role/org UI 删除路径的后端实现为硬删除，按协调者要求停在最终动作前；等待针对精确对象的 action-time 用户确认。两条文件记录均未移入回收站或清除，留待统一精确清理决定。
- 尚未测试普通 QA 用户编辑/角色与组织快照、角色分配、无角色回退、多角色逐维度最小值、停用角色计算、普通用户本人文件边界、小额额度上/下限与超额拒绝、资料字段选择/保存/清空、组织成员归属。管理员文件页只证明该管理员本人路径的一次真实 UI 上传/刷新/下载。
- **当前 UI 最终状态：** Edge 1021204180 停在管理员 `/dashboard/files`，新 synthetic 文件可见；可供窗口③继续安排。旧 retention 文件仍未见，根因未知。QA handoff tab 1021204172 未改动。

### 128 KiB 配额边界文件：管理员本人真实 UI 上传、刷新与下载（2026-09-29）

- 使用 Edge 管理员标签 **1021204183** 打开 `/dashboard/files`；QA handoff tab **1021204172** 未触碰。视口约 2390×1062。操作前页面显示用量 **126 B / 1 GiB、1 / 1,000**，与之前上传的 126-byte synthetic 文件相符。
- 上传源 fixture 为 `.runtime/quota-chunk-boundary/lumira-quota-chunk-boundary-20260929.txt`，选择前只读核验为 **131,072 bytes**、SHA-256 `a1c86be0736d21742f12fb89125695b9c17f8c4caf529d462e6e8d0d73bc57b4`。通过页面“上传文件”按钮打开 macOS 原生 Open 面板，在目录 `/Users/johntao/Documents/GitHub/lumira-next/.runtime/quota-chunk-boundary` 选择该唯一 fixture；没有使用 API 或 Playwright 文件选择器。
- 上传完成后页面显示新行 `lumira-quota-chunk-boundary-20260929.txt`、**131,072** 字节、“可用”；用量变为 **128.12 KiB / 1 GiB、2 / 1,000**，相对操作前增加一个 131,072-byte 文件。整页刷新后新行、状态与用量仍在。用文件名字段执行可见查询，结果为 **1 条**；该行的可见下载链接包含 file ID `d956b884-ddd4-43c7-8c7d-fc4439738553`。
- 通过该行可见“下载”链接发起下载。Edge Downloads 首次显示一个 `content.content` 项并标记“无法下载 - 已阻止”，旁边有可见“重试”按钮；随后仅通过该按钮重试一次，后续 Downloads AX/截图显示原先被阻止的项及已完成的 `.txt` 文件，并提供“打开包含文件夹”。通过 Finder 确认完成文件在 `/Users/johntao/Downloads/lumira-quota-chunk-boundary-20260929.txt`。下载件与源文件均为 **131,072 bytes**，SHA-256 均为 `a1c86be0736d21742f12fb89125695b9c17f8c4caf529d462e6e8d0d73bc57b4`。现有证据没有浏览器安全插页、Safe Browsing 说明、保留/允许提示或其它原因；因此不能区分安全拦截与普通传输失败，下载原因保持未分类。后续仅按既有截图/AX记载本结果，没有重新访问或操作该下载状态。
- 新文件记录保留在账号中，没有移入回收站或删除；fixture 本地原件也保留，可用于用户随后完成 QA 登录后的角色额度复测。执行结束时 admin tab 1021204183 已标记 handoff、释放 Edge 窗口；未触碰 1021204172，也未构建、重启或运行测试。
