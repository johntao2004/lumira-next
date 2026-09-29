# 2026-09-29 会话式 UI 验收补充

测试会话：已登录的 8020 QA 页面。未操作用户的 8010 页面、模型密钥或短信凭据；未使用接口写入替代页面操作。

## SMTP 本机投递

完整记录见 [SMTP 本机投递验收](smtp-local-delivery-acceptance-2026-09-29.md)。最终受控页面点击收到成功提示，接收端与 `mail.localTest.ACCEPTED` 审计行一致；临时 sink 已停止，SMTP 已经页面保存并重载恢复到未启用基线。

## 地区资料字段

- `/dashboard/profile-fields` 是**配置元数据**页。`Region` 的 `字段说明` 原值为空；只填入 `QA_PROFILE_REGION_DESC_20260929_B`，通过页面保存并“重新载入配置”确认仍存在；随后清空、保存、重载，确认恢复为空。这只验证字段配置。
- 管理员账户菜单→**个人资料**中的“地区”是普通文本框（`ID: region`），不是下拉框或选择器；原值为空。只输入 `QA_REGION_20260929`，保存、关闭抽屉、整页刷新后重新打开确认值持久化；再清空、保存、整页刷新并重开，确认为空。名称、姓名、出生月份、邮箱、手机、密码和权限均未改。
- 因运行中的控件是文本框，本次能证明该资料值的保存、刷新、清空和恢复；不存在可验证的“地区选项选择”操作。若产品需求要求地区下拉，需要先将控件实现为选择器并绑定稳定选项，不能把文本框保存测试记为选项选择通过。

## 本地化恢复与消费

- `/dashboard/localization` 当前选择简体中文后，搜索 `sensitiveImportCsvChooseFile` 的唯一行显示源文案 `Choose a CSV file`、有效文案 `选择 CSV 文件`、来源 `平台内置文案`、状态 `正常`。
- `/dashboard/audit` 可见实际事件 `localization.translation.restored`，目标 ID `dc227991-36c4-4123-8737-60c73e9cf312`，时间 `2026-09-29 18:11:01`。这解释了当前已存在的恢复记录。
- 刷新 `/dashboard/sensitive-words` 后重新打开 CSV 导入，选择 CSV 来源，控件显示默认 `选择 CSV 文件`；再次整页刷新并重新打开后，仍显示该默认值。敏感词表仍为 20 条。导入抽屉已关闭，没有选择或提交文件。
- 历史记录 `ui-acceptance-2026-09-28/03-ops-settings-locale.md` 记载的第一轮只打开了“恢复默认”确认框且未确认。因此在那一时点旧覆盖仍在且没有恢复审计，是确认框未提交的结果。后续有真实恢复事件并在当前审计页可见。旧页面在任何具体缓存机制上的成因没有独立证据，本记录不把缓存推测写成已证实原因。

## 短信、AI 与字号验收边界

- 短信页显示服务关闭、Webhook 地址为空、访问密钥未配置。未触发短信发送；在用户提供本地合成凭据前，该项仍被配置阻断。
- 用户表示将自行在模型接入页配置 CHAT 模型；本次没有保存/读取密钥。CHAT 对话生成、取消、长会话和 AI 翻译仍待模型配置后进行实际页面测试。
- 代码审计确认项目自带的 `scripts/design-qa-browser.mjs` 会直接调用 `/api/auth/login` 并读取 `.runtime/local.json` 中的引导密码来构造会话。这不符合本轮真实 UI 登录测试边界，因此未运行。当前可用 CUA tab 接口没有 CSS viewport override；本记录不把旧的 viewport 模拟或登录页实机读数外推为已完成的认证工作台 1920/2560/3840 验收。

## 服务与文件保护

SMTP 测试后，`node scripts/status.mjs` 显示 8010 frontend PID `76301`、8090 backend PID `49218` 均 HTTP 200。8010 未刷新或重启。临时 SMTP sink `127.0.0.1:29447` 已停止，未发现监听进程。五个用户指定的文件预览测试文件未修改、删除或清理。

本机会话中的 CUA 截图在工具结果中显示，但接口未提供将其保存为项目文件的能力；本证据文件没有声称存在独立 PNG 截图。

## 2026-09-29 21:12 Asia/Shanghai 组织筛选修复与维护模式页面闭环

### 组织列表筛选

- 根因是列筛选 SQL 被拼接在 `ORDER BY / LIMIT / OFFSET` 之后，PostgreSQL 将 `AND strpos(...)` 解析到 `OFFSET` 表达式中并报错。`OrganizationRepository.filtered()` 现把 `PageFilterSql` 插入分页后缀之前。
- `OrganizationRepositoryQueryTest.listPlacesColumnFiltersBeforeOrderAndPagination` 本次在主工作区重跑通过：1 test、0 failures、0 errors、0 skipped。主工作区 Maven 编译和打包已完成；当前 8090 后端运行修复后的 JAR，SHA-256 `d9b605c701e46e4578569eb197b564bddff617de048c5a4bef60de27f9a016f9`。
- 用隔离的 8020 管理员页面实际点击验证：用户列表→管理员编辑→组织归属，按编码 `QA_ORG_20260928_181851_C` 查询得到唯一一条目标组织；在查询框按 Enter 后仍为一条；点击重置后回到 6 条。没有勾选组织或保存用户，随后取消编辑。此路径未再出现筛选错误。
- 角色/组织选择器的嵌套表单修复仍只在隔离前端副本 `/private/tmp/lumira-next-qa-20260929`，用 `component={false}` 避免嵌套 `<form>`；此前 Node 24 TypeScript no-emit 检查通过，本轮 8020 真实 UI 搜索/Enter/重置可用。当前 CUA 无页面控制台读取结果，因此嵌套表单修复后的最新 console error/warning 数**未验证**，不将其记为零。

### 维护提示启停

- 保存前页面基线为标题/说明空、允许登录角色 0 个、结束时间空、维护关闭。以无角色、无结束时间的临时验收内容开启维护并保存；独立匿名浏览器重新读取 `/user/login`，实际看到了临时标题和说明。管理员设置会话仍可访问。
- 随后通过维护页清空标题/说明、保持 0 个角色及空结束时间、关闭开关并保存。整页重载后管理员表单仍为空且关闭；匿名页重载后维护提示消失，正常密码登录表单可见。未填写或提交普通用户凭据，所以普通账号的登录拒绝路径仍未端到端验证。
- 页面当前仍显示“结束时间（本地时间）”控件；这与用户提出的维护配置仅保留标题、说明和允许角色的简化方向不一致，仍是设计/实现待处理项。

### 运行边界与未完成项

- `node scripts/status.mjs` 当前显示 backend PID `62748`、web PID `76301`，8090/8010 均 HTTP 200。此次没有重启或导航用户的 8010 浏览器标签；8020 用于实际交互验收。
- 角色配额/用户资料/普通反馈等既有合成记录，敏感词 CSV 批次与 3 个新增演示词、本地化 QA locale/条目和合成反馈没有清理；按当前约束保留，须按精确清单及可恢复页面路径单独处理。五个文件 `preview-source.doc`、`preview-source.docx`、`preview-test.txt`、`preview-test.md`、`preview-long.txt` 及两份原有基线文件继续保留；永久删除、清空回收站和删除后自动刷新用量均暂缓。
- 原始模块边界检查仍记载既有 `platform -> settings` 依赖告警；配置消费者尚非全量闭环，例如普通用户资料/角色额度消费和 CHAT 驱动的翻译仍未验。此次组织筛选与维护提示通过，不能代替这些架构和消费者验收。
- 用户明确本轮先不配置 CHAT 模型，因此没有打开模型接入页、读取密钥或尝试启动对话。AI 聊天与 AI 翻译仍等待用户之后配置可用 CHAT 模型。
- 截图由 CUA 在会话工具结果中显示，但没有可用的本地保存接口；未在此处伪称已落盘 PNG。
- 上述验收只关闭了组织筛选和维护提示两个局部闭环，整批整改仍未全部验收。普通用户权限/额度/反馈和资料值、AI 会话、本地化 AI 翻译、监控隐藏态/精确请求周期、8010 新前端版本的实际验收等仍分别受凭据、CHAT 配置、可观测性或发布状态限制。

## 2026-09-29 21:32 Asia/Shanghai 模块边界修复、运行更新与消费者复核

- **边界告警纠正：**本轮主工作区源码中未发现此前记录的 `platform -> settings` 依赖。复跑 `python3 scripts/module-boundaries-smoke.py` 通过（15 个后端模块描述、54 个归属控制器及前端 registry contract）。实际失败边是 SMTP/SMS 测试控制器从 `settings` 指向 `communication`；控制器已迁到 `mail`/`sms` 包并标记为 communication endpoints，路径仍是 `POST /api/platform/settings/{smtp|sms}/test`。投递服务现在依赖 `MailConfigurationPort` / `SmsConfigurationPort`，由原 settings services 实现；原 `platform:settings:write` 权限检查与全局范围检查保留。
- **源码和安全验证：**主工作区重跑 `MailDeliveryTestServiceTest`、`SmsDeliveryTestServiceTest`、`DeliveryTestControllerSecurityTest` 共 10 项，0 failures、0 errors、0 skipped；测试确认非写入者在读取配置/凭据和调用网关前被拒绝。`./mvnw -q -DskipTests package` 成功。未再次发送邮件/短信，也未修改 SMS 配置。
- **8090 运行证据：**仅重启 backend 进程，从 PID `62748` 切换到 `66966`，readiness 为 HTTP 200。运行 JAR 为 `.runtime/platform-server-1790688684610-settings-boundary-fix.jar`，SHA-256 `71fb2c6d144a78e477d6379ebc0af9ad331a095079b34a6299833a99a1d81f47`。Maven 生成的内置语言目录写入 `backend/target/generated-resources`；`apps/web/src` 本轮没有修改。8010 web PID 仍为 `76301`，未导航、刷新或重启用户页面；数据库未清理或迁移。
- **设置消费实测：**在隔离 8020 的品牌设置页面将公司名称从 `Lumira` 改为 `Lumira QA Company 20260929`，页面提示保存成功；整页刷新仍保留。另用无痕窗口真实打开本地登录页，页脚显示该名称，确认公开登录页是实际消费者。随后通过相同表单恢复 `Lumira`、保存并重载确认恢复；4 条设置历史 revision 保留。
- **用户表单源码位置复核：**`users-page.tsx` 主工作区与 8020 副本 SHA-256 相同；共享 `FormDrawer` 中 `component={false}` 在两份源码都存在。额外的 `<AntForm component={false}>` 目前只在隔离副本 `apps/web/src/features/users/user-editor.tsx:402`，主工作区的 `user-editor.tsx` 不含该属性。此前将该行称为 `users-page.tsx` 的定位不准确。控制台错误/警告目前仍待允许的浏览器 console/devtools 读取，不以源代码或服务 PID 推断为零。
- **维护页补丁边界：**隔离副本删除了维护页 `endAt` 字段和 `localTime` 辅助函数，保留标题、说明、允许角色和启用开关。补丁见 `/private/tmp/lumira-next-qa-20260929/maintenance-page-review.diff`；Node `v24.21.0` `pnpm run typecheck` 退出码 0。主 8010 的 `maintenance-page.tsx` 仍保留结束时间控件；该补丁没有应用到主工作区，也尚不能计为已发布页面验收。
- **保留数据与剩余 UI 复核：**敏感词页面恢复未过滤的 20 条基线；精确搜索 `ALPHA`、`WITH,COMMA`、`WITH "QUOTE"` 均无记录，未做删除。临时 `zh-CN/sensitiveImportCsvChooseFile` 覆盖已不存在，当前为内置文案；`en-QA` 中 `localizationBundleFormat` 管理员覆盖仍存在。页面上的“恢复默认”提示称只清除该 locale override，但提交后返回“服务暂时不可用，请稍后重试”，原覆盖仍在；没有第二次重试、接口/数据库绕行或删除语言包。其余合成角色、组织、反馈以及三条演示敏感词均按要求保留。
- **CHAT 状态：**用户最新明确“先不配 CHAT 模型”。本轮未打开模型接入配置、读取密钥或发起 AI 对话/翻译；这些实测暂缓，不作为已完成。
- **当前全局状态：**后端边界源码/定向测试/8090 更新和品牌名称消费者闭环通过；组织筛选与维护启停仍是既有局部通过项。剩余包括普通用户凭据交接后的权限/配额/反馈实测、维护页隔离补丁真实页面呈现、浏览器 console 读取、监控隐藏态、locale 恢复错误路径、CHAT/翻译（用户暂缓）、8010 新前端发布验收和 Fast 实际运行档位确认。总体不能标记整批整改完成。

## 2026-09-29 21:41 Asia/Shanghai 8020 浏览器控制台实测

- 通过 Edge 菜单“查看 → 开发人员 → JavaScript 控制台”读取现有隔离 8020 SPA tab，没有刷新页面，也没有清除 console。
- 品牌页累积快照为 5 条：1 条 React DevTools info、3 条 warning（Ant Design Drawer 的 `width` 弃用提示，ProForm `initialValues` 异步加载说明各中/英文一条）、1 条 `/api/auth/csrf` HTTP 504 error。Drawer warning 调用栈在 `@rc-component/util` / `antd/es/drawer/Drawer.js`；当前 stack 展开未显示产品源码 frame。当前不归因这条 504：console 没有时间戳，Network 面板报告该请求已不可用，不能与 8090 重启窗口对齐，也没有重放请求。
- 真实点击用户列表→管理员编辑→组织归属后，累积快照共 6 条：1 info、4 warning、1 error。新增 warning 是产品源码 `apps/web/src/features/users/user-editor.tsx:133` 的 `useForm is not connected to any Form element`，调用栈为 `setTimeout → UserEditor → UsersPage → UserManagementTabs`。切到组织归属 tab 未再新增消息。没有出现明确 `<form>` 嵌套警告；因此 `AntForm component={false}` 对应界面的最新 console nesting 结果是未复现 nested-form 警告，但另一个未连接 Form warning 仍是待修源码问题。用户/组织选择保持原样、未提交。
- console 观测是同一 tab 的累积条数，不是各页面独立计数；品牌页基线错误是否来自服务重启仍未知。维护页与现有 locale 恢复失败请求的只读核验仍在进行。

## 2026-09-29 21:44 Asia/Shanghai 隔离维护页与本地化恢复请求复核

- 在现有 8020 admin SPA tab 通过侧栏“平台管理 → 个性化设置”点击“维护设置”。维护表单实际只显示标题、说明、允许角色和启用开关；另有历史/重新载入操作。AX 树与页面截图中均未见“结束时间/endAt”控件。当前标题/说明为空，允许角色为 0/100，列表 3 行且未选择，维护开关 off。没有保存或切换；这是隔离 8020 源码的页面验收，不表示 8010 已更新。
- 当前 DevTools Network 的“restore”筛选为 0/63 条。此前本会话未开启保留日志，原 `en-QA` restore 请求无法再读 URL、状态、响应或时间；后端日志中也无 localization/restore 命中。未重放或重试。该恢复失败的具体 HTTP 根因仍未知。


## 2026-09-29 独立 QA 收尾：8022 用户编辑器与本地化恢复

### 真实页面操作结果

- 在隔离预览 `http://127.0.0.1:8022` 使用管理员现有会话，进入 `/dashboard/localization`。通过页面筛选“English QA fixture”并精确搜索 `localizationBundleFormat`，确认操作前是 QA 管理员覆盖。只执行了一次页面“恢复默认”并在确认框再次确认；界面随后显示“语言包格式版本”，来源“回退文案”，恢复操作不再显示。
- 整页重载后重新筛选相同 locale/key，值和来源仍为“语言包格式版本 / 回退文案”，状态正常，确认页面写入已持久化。导入语言包面板显示真实消费文案 `语言包格式版本: v1 · 源语言 · 回退语言 · messages`；没有选择或导入文件，也没有更改管理员个人界面语言。审计页可见对应 `localization.translation.restored` 事件。
- **HTTP 细节未捕获。** 当前浏览器控制接口没有 Network 请求/响应读取能力；因此没有实际观察请求状态码。代码声明的请求为 `DELETE /api/platform/localization/translations/en-QA?messageKey=localizationBundleFormat&version=…`，控制器标注成功响应为 `204 No Content`。这只是源码中的预期契约，不是本次实际 HTTP 观测。
- 在新隔离 `8022` 用户列表中只打开管理员的编辑抽屉，没有保存任何字段或分配。最终补丁版本首次打开时，筛选 console 结果未见 `useForm is not connected to any Form element`。较早的 8020 浏览器快照曾出现同一警告；该快照属于最终验证之前，不能用来否定 8022 对最终源码的单次复测，也不能扩展成“所有 console 零告警”。隔离前端 Node `v24.21.0` 的 `pnpm run typecheck` 已退出 0。

### 预览端口、真实源码目录与补丁对应

- 本次新验收会话是 `8022`，监听 PID `73444`；`lsof` 的进程 cwd 为 `/private/tmp/lumira-next-qa-20260929/apps/web`。用户此前看到的隔离 `8020` 当前监听 PID `21602`，cwd 也是该 QA 副本。两个预览进程都加载这个临时源目录；`apps/web/node_modules` 是指向主工作区依赖的 symlink。它们的源文件不在 8010 服务目录。
- 当前 `8010` 监听 PID `76302`，cwd 为 `/Users/johntao/Documents/GitHub/lumira-next/apps/web`。本次没有访问、刷新、重启该页面或服务。之前记录的 8010 功能 chunk 仍是旧 bundle；没有本轮新的 8010 bundle 读取，因此旧记录不冒充当前刷新后的取证。**将候选版本更新到 8010 的维护窗口尚未得到确认。** `8090` backend 当前监听 PID `66966`，保持运行；没有停止任何现有服务。
- 隔离候选中的用户文件预览、下载确认、MB 大小格式和管理端状态列源码与主工作区对应文件 SHA-256 一致；没有额外 file-source delta。已有 [`file-preview-and-download-followup-2026-09-29.md`](file-preview-and-download-followup-2026-09-29.md) 记录 8020 的真实页面预览、长文滚动及下载确认/取消结果。本次未重开文件列表复核 MB/status 的视觉呈现。
- 模型表单候选改为模型发现后的选择控件，去掉手动能力复选项，并处理已保存模型、空选择校验和凭据复用边界；本轮没有打开该页、读取密钥、执行模型连接或配置 CHAT。完整候选补丁涵盖 AI 对话/模型页、模型类型与发现/校验、发现单测和 8 种语言消息：`/private/tmp/lumira-next-qa-20260929/model-form-simplification-review.diff`。
- 维护表单候选移除结束时间和本地时间转换函数，保留标题、说明、允许角色及开关；本轮未在 8022 保存维护设置。补丁：`/private/tmp/lumira-next-qa-20260929/maintenance-page-review.diff`。
- 用户编辑器候选避免在抽屉关闭/数据加载时访问未连接的 Form，编辑器打开后再挂载表单，并以受控状态判断资料字段是否修改；角色与组织选择器用独立筛选项。补丁：`/private/tmp/lumira-next-qa-20260929/user-editor-warning-review.diff`。上述模型、维护和用户编辑器差异均只存在候选 QA 副本；没有应用到 8010 主前端。

### 本轮保护边界与仍待处理

- 五个预览测试文件 `preview-source.doc`、`preview-source.docx`、`preview-test.txt`、`preview-test.md`、`preview-long.txt` 和两份原始基线文件均保留。本次未永久删除文件、清空回收站或执行 purge；永久删除后的异步清理与用量刷新验收继续暂缓。
- 普通用户 QA 新账号尚未创建；角色和配额调整未获批准，未注册账号、填密码、赋权或改配额。CHAT 本轮明确暂缓，没有查看或写入模型密钥，也没有发起对话/AI 翻译。
- 独立收尾确认了本地化恢复持久化、回退文案消费者和用户编辑器首次打开警告复测。此前整改清单仍**不能整体标记完成**：8010 候选更新、普通用户角色/配额/反馈实测、CHAT 对话与 AI 翻译、永久删除清理验收，以及其它未闭环页面仍待后续。
- CUA 展示过页面截图，但没有可用的本地 PNG 保存接口；没有将截图路径伪报为交付文件。

最新覆盖报告：`/Users/johntao/Documents/GitHub/lumira-next/docs/audits/evidence/qa-session-followup-2026-09-29.md`。本节对应的可审阅补丁：
- `/private/tmp/lumira-next-qa-20260929/model-form-simplification-review.diff`
- `/private/tmp/lumira-next-qa-20260929/maintenance-page-review.diff`
- `/private/tmp/lumira-next-qa-20260929/user-editor-warning-review.diff`

## 2026-09-29 08:14 America/Los_Angeles 模型发现权限规则与 8090 更新

- 在主工作区 [`SecurityConfiguration.java`](../../../../backend/src/main/java/com/lumira/platform/identity/SecurityConfiguration.java) 增加两条最小权限规则：`GET /api/platform/ai/providers/*/models` 要求 `platform:settings:read`；`POST /api/platform/ai/providers/discover-models` 要求 `platform:settings:write`。`anyRequest().denyAll()`、CSRF 与其他权限规则保持不变。
- 定向测试 `./mvnw -q -Dtest=OrganizationRepositoryQueryTest,DeliveryTestControllerSecurityTest test` 退出码 0：3 个测试、0 failures、0 errors、0 skipped。`./mvnw -q -DskipTests package` 退出码 0。运行包 SHA-256：`d7ca9dc91c8df62546c92ae297c54c81c01056ebe52ce5e452ed325888fa8aa9`。
- 只重启后端：8090 PID 从 `66966` 更新为 `81412`，readiness 为 HTTP 200；8010 前端 PID `76301` 保持不变，前端与后端 health 均为 HTTP 200。旧运行包保留用于回退。检查显示旧 JAR 之后只有 `SecurityConfiguration.java` 改动；本轮没有数据库迁移文件或登录会话设置变更。
- 通过隔离 `8022` 的管理员会话从工作台点击“AI 中心 → 模型接入”，页面正常加载。列表只有已保存的 `Jev / TypeSafe Jev / jev-latest`，显示密钥已加密保存、状态正常。没有打开凭据、修改模型或发起外部连接。
- 用户最新决定是暂不配置 CHAT。现存服务是 TypeSafe Jev 决策模型，不满足 CHAT 模型列表接口的前置条件；模型发现表单又需要有效服务凭据。为遵守不新填或修改密钥的边界，本轮没有执行真实上游模型发现，也没有聊天生成或 AI 翻译。新增安全规则已编译并部署，但真实模型列表/发现结果仍待 CHAT 服务与既有凭据可用后在 UI 中验证。
