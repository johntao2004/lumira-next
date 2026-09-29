# 代码审查 · 2026-09-16（含 12:30 复核修订）

审查对象：`lumira-next`（后端 205 个 Java 文件 / 约 10,075 行；前端 104 个 TS/TSX / 约 11,419 行；单文件 SQL 基线 89 KB）。

> **⚠️ 阅读前必读**
> 初版有 **5 条结论经回源核实后不成立或已被修正**，另有 **1 处统计错误**。
> 且**审查期间代码库被并发修改**（11:27 / 12:23 / 12:28 三批），部分行号已失效。
> 请以文末「复核修订」一节为准，该节优先级最高。

## 当前 checkout 复核补充（2026-09-16）

在上述审查之后，当前代码已继续完成以下修订：

- Cookie Secure 默认值已改为 `true`，本地 HTTP 由启动脚本显式选择关闭；CSP 与可选 HSTS 已加入前端响应头。
- 通知批量写入、邮件/短信租约续期、共享投递 Store/Worker、配置端口和 15 个前后端模块描述符已落地；V1 的 37 张平台表、视图和函数已有模块归属清单。
- `AdministrationGuard` 已从单一 481026 锁改为权限域分桶锁；设置/模块保留共享锁，文件管理读路径改为无锁 `requireGlobal`。
- 前端列表状态、分页契约、错误映射、日期格式和 API DTO 已下沉到平台层；列表状态由 `useListRequest` 统一，`ResourceTable` 不再承担数据契约。

因此，下面早期表格中的 P1/P2 行号保留作为审查时点证据；判断当前状态时以本节、代码和最新验证记录为准。

---

## 总体判断

工程质量**高于典型脚手架**。全局正则扫描 + 抽样精读确认：

- SQL 注入：**未发现**。所有 SQL 拼接片段均为编译期常量，无用户输入进入 SQL 文本；无 MyBatis，无 `String.format` 拼 SQL，无动态 ORDER BY。
- 硬编码密钥 / 默认密码：**未发现**。密钥来自 `LUMIRA_SECRET_KEYS`，缺失时功能禁用而非回退明文；仓库无 `.env`/`*.key`/`*.pem` 残留。
- 弱随机数：**未发现**。验证码用 32 字节 `SecureRandom`。
- 路径穿越：**未发现**。Blob 用 UUID 分片 + `NOFOLLOW_LINKS`。
- 越权：默认拒绝 + 服务层二次校验 + `platform_visible_users` 数据范围函数收敛 IDOR。
- 乐观锁确实生效（`version` 进入 UPDATE 的 WHERE 子句）。
- 日志泄露：仅输出 UUID 与异常类名，无密码 / token / 正文。

---

## P1（应修）

### 1. 会话 Cookie 默认不启用 Secure —— ✅ 成立（文件稳定）
`backend/src/main/resources/application.yml:31`
```yaml
secure: ${LUMIRA_COOKIE_SECURE:false}
```
`deploy/compose.yml:26` 同样是 `LUMIRA_COOKIE_SECURE:-false`。两处默认值叠加，生产漏配即明文传输会话。
**改**：默认值改 `true`，本地 profile 显式关闭；或 prod 启动 fail-fast 校验。

### 2. 全局 advisory 锁，管理端读写全串行 —— ✅ 成立（其中一半已于 12:28 被修复）
`backend/.../identity/AdministrationGuard.java:15`
```java
jdbc.sql("SELECT pg_advisory_xact_lock(481026)").query(...);
```
`guard.require()` 在约 20 个 Service 的写路径上取同一把库级锁。补充核实发现范围比初版更大：

- `NotificationAdminService.publish` 带 `@Transactional`，锁横跨整个广播事务（**逐条 INSERT 部分已于 12:28 改为 `store.createBatch(requests)`**）。
- `FileService.listManaged`（`@Transactional(readOnly=true)`，:113-114）与 `findManaged`（:118-119）是**读路径**也取这把写锁，管理员文件列表会与管理端写操作互相阻塞。
- 反例：`AuditService` / `MailDeliveryQuery` / `SmsDeliveryQuery` 用的是 `requireGlobal`，**不取锁**，无此问题。
- 注意：`pg_advisory_xact_lock` 是事务级锁，调用方若无 `@Transactional`（如 `findManaged`）会在 autocommit 下立即释放，此时锁是空转。

Hikari `maximum-pool-size: 12`，长时间持锁会把管理端操作堵死。
**改**：锁按 section 分桶（如 `481026 + section.hashCode()`）；读路径改用不取锁的 `requireGlobal`。

### ~~3. 邮件租约无续期会丢失投递结果~~ —— ❌ 初版结论不成立，已删除
核实推翻：`SmtpGateway.java:27` 已设 `connectiontimeout=5000`、`timeout=10000`、`writetimeout=10000`，整个 SMTP 操作上限约 15–25 秒，远小于 2 分钟租约；且 `source.load()` 只读取配置、**不建立网络连接**（初版称"SMTP 连接无超时"是错的，真正的网络操作 `gateway.send()` 被 60s 看门狗覆盖）。
保留为 P2：租约确实无续期机制（`lease_until` 仅在 claim 时设置一次），属健壮性改进而非当前缺陷。

### ~~4. 导航排序无并发保护、reorder 不带 version~~ —— ❌ 误报，已删除
核实推翻：前端 `navigation-page.tsx` 的 `toggle`(:182)、`remove`(:195)、`move`(:209) 三者均有 `actionPending` 守卫、设置 `pendingMutation`、按钮 `disabled={actionPending}` + `loading`，且 reorder 负载**带 `version`**（:218-219）、409 时自动 `query.refetch()`。后端 `NavigationMenuService.reorder`(:193) 也用 `version` 做乐观锁，不匹配返回 409 `staleRecord`。实现是正确的。

---

## P2（建议改进）

| # | 位置 | 问题 | 复核 | 建议 |
| --- | --- | --- | --- | --- |
| 5 | `notifications/NotificationService.java:35-38` | `quote()` 手写 JSON 转义，未处理 `\t` 及 U+0000–U+001F | ✅ 成立 | 改 `ObjectNode.put()` |
| 6 | ~~`platform/providers.tsx` t 未 memo~~ | — | ❌ 误报 | :134 `useCallback`、:140 `useMemo` 已存在 |
| 7 | `features/account/profile-drawer.tsx:51-53` | 用**英文文案相等判断**决定是否走 i18n | ✅ 成立（mtime 09-15，稳定） | 后端下发 `labelKey` |
| 8 | ~~`navigation-page.tsx` 父级下拉可成环~~ | — | ❌ 误报 | :78-99 `excludedParentIds` 已递归排除子树 |
| 9 | `localization-page.tsx:243`（`maxLength={8_000_000}`）、`sensitive-words-page.tsx:198`（`1_000_000`） | 粘贴大文本卡死主线程 | ✅ 成立 | 降到 200 KB 量级，改文件上传 |
| 10 | 6 个 CRUD 页面骨架重复 | `useState<ListRequest>` + `useQuery` + `selected` 三态 + `FormDrawer` 逐字复制 | ⚠️ 未逐页复核 | 抽 `useCrudDrawer()` |
| 11 | 散落多份 `errorKey()` 与 `Intl.DateTimeFormat` | 重复实现 | ⚠️ 未精确计数 | 抽公共 hook |
| 12 | `mail/MailDeliveryStore` 与 `sms/SmsDeliveryStore` | 七个方法近乎逐行复制 | ✅ 成立 | 抽泛型 `AbstractDeliveryStore` |
| 13 | ~~`workspace.tsx` effect 依赖 `user.data`~~ | — | ❌ 已于 12:24 修正 | 现为 `[user.data?.username]` |
| 14 | `common/SecretKeys.java:15-17` | 缺密钥时静默 `cipher=null` | ✅ 成立，但**注释表明是有意设计**（"An absent ring disables secret operations, not unrelated platform features"） | 仅建议加启动 WARN，非缺陷 |
| 15 | `files/FileService.java:90-93` | `abandonUpload`(事务1) → `blobs.delete` → `finishDelete`(事务2) 三步分离，可能留孤儿行 | ✅ 成立 | 合并单事务或加对账 |
| 16 | `apps/web/next.config.ts` | 已配 `nosniff`/`DENY`/`Referrer-Policy`，缺 CSP 与 HSTS | ✅ 成立 | 补两个响应头 |
| 17 | 类型安全 | `as MessageKey` 强转与 `!` 非空断言 | ⚠️ 未精确计数 | 词典查表替掉 `as` |

---

## 已排除的疑点（复核确认，勿重复返工）

- **品牌链接 XSS**：`branding/provider.tsx:17` 直投 `<a href>`，但后端 `Branding.java:12,16` 调用 `SettingUrls.valid()` —— 拒绝控制字符与反斜杠，只允许 `http`/`https`（带 host、无 userInfo）或非 `//` 开头的相对路径。`javascript:` 无法写入。（文件 mtime 09-09，稳定）
- **通知链接开放重定向**：`notifications-button.tsx:42` 直投 `<Link>`，但后端 `NotificationAdminService.normalizeHref()`(:102-107) 与 `NotificationStore`(:71-72，含批量路径) **双重校验**「必须 `/` 开头、禁止 `//`、禁止换行、≤512 字符」。
- **`workspace.tsx` 26 个元素**：只创建 element 对象，React 只渲染选中项，开销可忽略。

---

## 架构层面

1. **单文件基线 `V1__platform_initialization.sql`**（89 KB）
   核实后的准确构成：**39 张表 = 37 张 `platform_*` + 2 张 `spring_session*`**、5 个函数、**49 个索引**（40 普通 + 9 唯一）、3 个视图、85 条 ALTER、7 组种子。
   （初版误写为 52 个索引；"39 表"与文档"37 张平台表"不矛盾，差的是 2 张 Spring Session 表。）
   代价：**生产没有增量迁移路径**，上线后变更只能备份重建或另建迁移目录。上线前必须定演进与回滚方案。
2. **`db:reinitialize`** 有显式开关 + 自动备份 + 管理员凭据走环境变量，设计克制；但 `dropdb --if-exists` 具破坏性，建议加目标库名白名单防误连生产。
3. **`AdministrationGuard` 全局锁**（见 P1-2）是"管理端写操作串行化"的架构决策，需在吞吐目标下重新评估。

---

## 复核修订（2026-09-16 12:30）—— 优先级最高

主人要求核实初版结论，逐条回源后的结果：

### 被推翻（5 条）
| 原结论 | 核实结果 |
| --- | --- |
| P1-3 邮件租约丢结果 | ❌ 不成立。`SmtpGateway` 有 5s/10s/10s 超时，SMTP 上限约 15-25s，2 分钟租约绰绰有余；`source.load()` 不建连接 |
| P1-4 导航排序无保护 | ❌ 误报。`actionPending` / `pendingMutation` / `version` / 409 重试全部到位 |
| P2-6 providers `t` 未 memo | ❌ 误报。:134 `useCallback`、:140 `useMemo` 已存在 |
| P2-8 父级下拉可成环 | ❌ 误报。:78-99 `excludedParentIds` 已递归排除子树 |
| P2-13 workspace effect 依赖 | ❌ 已被修正为 `[user.data?.username]` |

### 统计错误（1 处）
索引数 **49**（非 52）；39 表 = 37 张 platform 表 + 2 张 spring_session 表。

### 仍然成立（4 条）
P1-1 cookie secure 默认 false；P1-2 全局 advisory 锁（且读路径也取锁，比初版描述更广）；P2-7 英文文案比较；P2-9 超大 maxLength。

### ⚠️ 审查期间代码被并发修改
以下时间戳显示代码在我审查期间被改动，且**改法与本报告建议高度一致**：

- `11:27:42` `navigation-page.tsx`、`providers.tsx` —— 原 P1-4、P2-6、P2-8 三项恰好都是"已被修正"
- `11:28:46` `NotificationStore.java`
- `12:23–12:26` `workspace.tsx`、`routes.ts`、`registry.tsx`、多个 `*Controller.java`
- `12:28:20–12:28:43` `MailDeliveryStore.java`、`MailDeliveryWorker.java`、`NotificationAdminService.java` —— 广播由逐条 INSERT 改为 `store.createBatch(requests)`（已确认 `createBatch` 与 `CreateRequest` 实现完整，href 校验保留）

**这意味着有另一个会话/进程正在按本报告修改同一批文件。** 存在互相覆盖的风险，需先确认是谁在改动、是否已编译验证。

### 本轮实测
`node --test tests/*.test.mjs`：**17/17 通过**（Node 22.22.2）。项目声明 `engines.node >=24 <25`，故 `pnpm typecheck` / `build` 未执行。文档声称的后端 `clean verify` 与浏览器套件**未获独立验证**。
