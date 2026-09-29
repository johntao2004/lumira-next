# 公共账号草稿

迁移来源：旧 UserDraftController / UserDraftAppService / JdbcUserDraftRepository。仅迁移通用账号草稿存储，不迁移赛事和项目表单。

## 契约

- GET `/api/account/drafts/{key}`：本人草稿；不存在返回 204。
- PUT 同一路径：`{revision, payload}`。首次创建 revision 为 null；更新必须携带读取到的 UUID 修订号。返回 `{revision,payload,updatedAt}`。
- DELETE 同一路径并带 `?revision=...`：要求匹配当前修订；不存在幂等返回 204。
- 草稿键为 1–128 位小写字母、数字、点、下划线、冒号、横线，首位为字母数字。
- payload 接受非 null JSON；UTF-8 序列化内容最多 1,000,000 字节。HTTP 请求体另有 1,004,096 字节上限，反序列化前拒绝超限。
- 每账号最多 50 份、总计 5,000,000 字节；替换按差额计费，删除释放配额。旧实现没有账号总量限制，新限制用于防止无界存储。

## 并发和权限

从已认证用户名解析当前有效账号 UUID，不接受客户端 owner。账号删除后级联删除草稿，同名新账号无法读取旧内容。写操作按账号行锁串行检查版本和配额；不同账号互不占用同一行锁。

每次保存生成新的 UUID 修订，避免删除重建后的旧版本覆盖。版本冲突返回 staleRecord；配额超限 draftQuotaExceeded；输入错误 invalidDraft；超大 draftTooLarge。写操作保留 CSRF 校验，审计只保存动作与记录编号，不保存键或内容。

## 完成边界

当前为服务端适配阶段。共享前端恢复/保存交互、公共表单接入及全流程浏览器验收尚待实现。接口不沿用旧无版本覆盖行为，调用端需显式处理冲突，不能自动覆盖。

## 验证记录

新增 7 项真实 PostgreSQL 专项：账号隔离、版本冲突及删除重建、UTF-8 单份大小/份数/总量边界、配额回收、JSON 转义内容往返、同名账号重建、审计失败回滚与并发创建单一成功。首次完整 clean verify 通过，随后响应契约及存储调整后草稿专项 verify 通过；当前报告合计 184 项，失败/错误/跳过为 0。前端类型检查、13 项测试及生产构建通过。V33 已在本地新脚手架数据库应用。

运行版本 HTTP 回归 drafts-smoke.py 通过：未登录拒绝、空草稿 204、保存读取一致、账号隔离、CSRF、键和请求大小限制、旧版本保存/删除拒绝及字段投影。夹具与会话已清理；脚本已加入 CI 配置，远端 CI 尚未执行。

## Frontend completion supplement

Supersedes the earlier frontend-pending statement: shared save/restore controls now exist, with organization creation as the first integrated public form. Other public form adapters remain incomplete; the management entry is covered in the later supplement.

Browser regression passed: explicit restoration after reload; cross-tab stale writes rejected without losing current input; reload and restoration of the newest draft; 390px drawer bounds and screenshot review; successful resource submission clears only the consumed revision. A newer revision from another tab survives cleanup and produces a warning. Reopening and submitting that newer revision then clears it correctly. Temporary accounts, organizations and drafts are cleaned up.

## Draft management entry

GET `/api/account/drafts?page=1&pageSize=20&search=...` returns a consistent owner-scoped page of `{id,draftKey,revision,payloadBytes,updatedAt}`. Payload is excluded. Search is a literal case-insensitive key substring, with shared pagination bounds.

The account menu now opens a shared ResourceTable/PlatformDrawer list, with search, pagination and revision-checked DeleteAction. Tree-mode desktop header and mobile/dual account menus expose the same entry. A stale delete reloads the list and displays the existing conflict error; it does not silently retry deletion.

The database suite now contains 8 passing tests, including 25-row pagination, owner separation, stable page boundaries and literal search. Runtime HTTP and browser pagination/search/delete/conflict validation passed. Desktop tree header, mobile account menu and desktop dual-navigation account menu all passed real browser checks.

Final frontend checks: typecheck, all 15 tests and production build passed. The populated 390px list screenshot was reviewed; table content scrolls inside the drawer. Browser tests use isolated fixtures and clean up account drafts and organizations.
