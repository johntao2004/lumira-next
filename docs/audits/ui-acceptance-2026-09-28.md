# Lumira Next 真实用户操作最终验收

日期：2026-09-28–29（Asia/Shanghai；验收跨日）  
项目：`/Users/johntao/Documents/GitHub/lumira-next`  
负责人：协调、排班、复核与汇总；页面操作和必要修复由显式配置 `gpt-6-luna / max` 的执行者实施。

## 验收原则与证据层

以页面真实操作为功能验收依据：点击、填写、选择文件、提交、刷新、检查最终数据与实际效果。源码审阅、自动化/单元测试、构建、HTTP health、页面渲染、业务交互、刷新后持久化、精确清理分别记录，不相互替代。不得使用直接 API 上传/导入、API 登录、注入会话、直接数据库写入或代码填充前端状态代替页面验收；API/DB 只读证据可用于定位/计数，并注明证据层。

每项记录真实路由、身份和权限、视口、操作步骤、预期/实际、刷新结果、截图/下载文件路径、环境版本、精确合成数据、清理结果与未覆盖边界。所有临时数据必须唯一、中性、可识别，仅按精确标记清理。保留原有账号、文件、设置、敏感词演示数据、历史审计和日志；维护模式为全局配置，只在普通用户、配额和反馈流程全部完成后最后验证，并恢复所有原值与正常登录。

结论标签：**通过**、**修复后通过**、**失败**、**配置阻断**、**工具阻断**、**未测试**。被跳过的自动化测试如实单独列示，不能计为通过。

## 矩阵与执行顺序

| 顺序/领域 | 本轮真实 UI 验收范围 | 执行窗口/状态 |
|---|---|---|
| 1. 用户、角色、组织、配额 | 编辑内角色/组织分配；权限按资源分组及只读/子操作；无角色回退、多角色逐维度最小值、角色高于系统默认值、停用/未分配忽略；小文件额度边界、上传、用量、下载、刷新及角色变更；组织树父子关系与用户归属 | 窗口① quota_users；admin 详情修复后真实加载。后续显式授权下已用 UI 建立一个 `SELF`、零 authority 配额角色并验证编辑/刷新/启停，以及一对空成员父子组织并验证树/编辑/刷新；硬删除未执行，精确对象待 action-time 确认。管理员本人文件已用 UI 上传/刷新/下载 SHA 闭环；旧 20B 待清理文件在上传后消失，原因未证实，未被执行者触碰。QA 普通用户隔离、角色分配/多角色额度、超额边界和资料字段值仍未测，凭据交接标签 1021204172 仍待用户完成；详见窗口①证据 |
| 2. AI 对话与模型 | Ant Design/X 布局，窄屏/展开侧栏/长文本/Sender/Bubble/会话/模型表格；可用配置下验证选择、生成、取消、错误、长对话和重载。先核实非敏感 capability 元数据；CHAT=0 时将依赖 CHAT 的生成、取消、AI 翻译明确列为配置阻断，不伪造结果 | 管理员 UI 已在真实 942×794 CSS px 视口查看 `/dashboard/ai-console` 与 `/dashboard/ai-models`。本轮表格仅一条 Jev / TypeSafe Jev / jev-latest，状态“正常”、凭据状态“已加密保存”；按本地 `providerCapabilities` 映射该类型仅有 CHOICE/SCORE/NOUL，故 CHAT=0。表格本身不显示能力列；未打开行操作/测试或任何密钥。AI生成/取消/对话/翻译仍受 CHAT 配置阻断 |
| 3. 运行监控 | 3/5/10/30 秒、1/5/15 分钟、1 小时、手动切换；刷新节奏、真实 hidden 暂停/恢复、离路由停止/返回、失败后恢复；“实时”机制文案 | 本轮 owned tab 1021204187 可见切换实时/10秒/手动；实时与10秒档页面更新时间前进，手动15秒未变；route leave/return也以可见UI确认。当前无request trace，页面时间戳不证明精确网络频率或路由请求停止/恢复。此前 hidden-window采样仍因`visibility=visible`、无tab ID而无法判定；当前可见但未聚焦状态无法用已支持CUA可靠建立。失败恢复未注入。详见02 |
| 4. 业务字典 | 反馈状态/分类/优先级/类型、资料字段 SELECT、V15 sensitive_word_category 的管理及实际消费；启停/默认、引用保护（包括主字典整体禁用）、权限、刷新持久化；固定安全/系统枚举保护；实证旧 TREE 保护项现状 | 窗口②的 V15 引用保护、停用持久化、启用选项排除和精确删除已通过；窗口③完成管理员反馈创建/处理和资料字段 SELECT 按名/编码搜索。仍缺“既有禁用值保持”消费者：反馈详情类别只读、处理表单只有状态/回复；需要已引用停用值且有正常 UI 编辑入口的合成记录，当前未找到。 |
| 5. 敏感词 | UI 简化、原生文件选择 CSV 导入、分类选择/过滤、禁用旧分类原样更新与向禁用分类迁移拒绝、检测与表格搜索分离；至少21个唯一启用 LOG_ONLY 加 tail BLOCK 使总命中≥22，显示20、整体阻断及隐藏命中提示；按精确标记清理，保留8既有与12演示词及软删历史 | F-01检测、真实反馈表单拦截与精确清理通过。quoted CSV经原生 Open + 页面 preview/confirm 导入：3 imported / 1 duplicate / 0 invalid，刷新后23条；随后内容检测单独命中保留的3词，均为仅记录，未创建新业务数据。batch 与3个 synthetic 词仍保留，等待 action-time 清理。精确 UUID 见02与清理清单 |
| 6. 操作审计及宽表格 | 长文本/依赖列省略与 Tooltip、内部横向滚动和操作可达、窄屏、默认10行与页大小调整、过滤列与数据列一致、动作列不参与搜索 | `/dashboard/audit` 已在真实 942×794 CSS px 视口检查；1,429 行、默认10、可调页大小/分页 ellipsis，筛选、重置、横向表格滚动和 `用户管理 · setting.update` tooltip 可见。数据表无行操作列。宽表行数/过滤另见03；无真实用户导出 |
| 7. 工单反馈 | 管理员默认“全部反馈”、第二 tab“我的反馈”；普通用户仅看到/取得自己的反馈，后台范围受限；刷新、空结果、请求失败不崩溃；普通用户提交与管理员处理闭环；不发送外部通知 | 管理员闭环通过。合成 marker QA_ADMIN_FEEDBACK_LIFECYCLE_20260929_A 创建于2026-09-29 05:49、提交者管理员；All 精确筛选唯一一条，当前“处理中”，刷新后 All/My 均持久。UUID 未取得且不再追查，保留待精确清理确认。普通用户范围/提交依赖用户在保留标签1021204172设置新临时密码并登录；403恢复还需受控的无 feedback 读取权限身份。通知只读，无通知创建/邮件/SMS。详见03 |
| 8. 平台模块与导航树 | 核心/可管理 tabs、audit-console 固定保护；依赖长文本、禁用关系/提示、窄屏；导航与组织树布局一致、树展开/折叠/祖先筛选 | Core 12 / Manageable 3、audit-console 当前核心行无停用控制（历史含旧停用记录）；真实窄屏 navigation 搜索祖先/子行、Reset、横向滚动通过。窄屏 organizations 搜 child code 保留 parent，reset后5行基线、操作列可达。既有父子合成组织已建并编辑刷新，保留对象见01 |
| 9. 水印及普通设置 | 移除日常设置原因字段、高风险原因保留；固定模式原文输出/个性化动态模板/图片；字号角度窄屏；保存刷新、未挂载字段保留；SMTP/SMS 无普通原因字段，仅授权本地 mock 测试 | 水印与站点名保存/consumer/refresh/restore 已通过。其余未覆盖设置需逐项明确保存、消费者、刷新与恢复用例；SMTP/SMS 验证依赖已配置且仅指向本地 mock/sink 的环境，本轮未连接或发送外部消息。详见03 |
| 10. 资料字段 | 合理字段名；SELECT 专用分页20字典选择器，按名/编码搜索并仅用启用字典；权限失败回退编码文本；停用/页外原值保留；合成用户资料值选择、保存、刷新、清空，引用保护；保留4内置字段，不改管理员个人资料 | 管理字段元数据已恢复到4/100；选择器按 sensitive_word_category 编码和敏感词分类名称搜索通过，草稿未保存。用户值保存/刷新/清空、权限失败回退和既有停用值显示依赖用户在1021204172完成临时 QA 账号创建和登录，之后使用安全合成 profile 值。详见03 |
| 11. 个性化、协议及维护 | 协议/维护归属个性化 tabs，旧 route/permission 兼容；维护标题/说明/允许角色、窄屏和登录角色；最后打开，保留管理员恢复途径，恢复所有原值并验证正常登录 | 窄屏 tabs、Agreements 空态及 Maintenance 只读字段通过；当前维护仍关闭，未保存/切换。启用效果、受限登录及恢复正常登录必须等 QA 普通用户、配额和反馈步骤完成后作为最后窗口，并需可用 QA 凭据。详见03 |
| 12. 本地化 | 文案列出、新增/导入真实效果、可扩展 locale、完整导出/导入/冲突预览/异常包及回退；原生文件选择导入；普通用户隔离；AI 翻译随 CHAT 可用性分类；精确清理新语言/条目 | zh-CN no-op、固定文本切换、en-QA 导入/覆盖/重复跳过、无效包拒绝及 fallback 已通过。两个测试值和 locale 保留待 action-time 清理确认；UI 无 locale 删除入口。普通用户隔离依赖 QA 登录；AI 翻译依赖当前未配置的 CHAT provider。详见03/04 |
| 13. 原功能完整性 | 依据综合审计覆盖文件、身份、安全、通知、事件、设置、AI 等原路由，检查操作闭环/空壳/保存效果；安全验证合成用户 CSV 导出候选时严格限制单条 UI 筛选，只检查原始字节并 UI 清理 | admin home/files/users/audit/modules/navigation/event-outbox/notifications/settings/profile/localization/AI 等路由已部分只读或窄范围检查；管理员合成文件 hash 闭环通过。完整普通用户流程依赖 QA 登录；CSV 导出仅在出现唯一合成候选后用 UI 检查，不导出真实用户。 |

### 共享执行排班

1. 窗口①：用户、角色、组织和配额的源码/权限预检完成；admin 详情缺陷已作窄范围修复，并在真实 Edge UI 复测后加载。管理员 UI 测试已验证零 authority `SELF` 配额角色的编辑、额度保存/刷新和启停；建立父子空成员组织并验证编辑/树刷新；个人文件上传、刷新与仅下载新 fixture 后的 SHA-256 一致。精确 role/org/file 均未删除，需协调者在汇总时处理 action-time 清理确认。QA 凭据交接仍是 Edge Profile `用户配置 1` 的标签 **1021204172**、`/dashboard/users`，用户名/名称已填、密码框目视为空、未提交；等待用户亲自输入新临时密码并保存、登录后再测普通用户、角色分配/多角色配额和资料字段。此前未定向到 Lumira 的粘贴尝试仅作历史保留；交接后续未重复该事件，也未分享或读取密码。
2. 窗口②：监控、字典、敏感词；已完成的真实页面检查和精确清理由 monitor_dict_sensitive 完成并曾释放共享浏览器。剩余监控子项仍待受控验证；CSV入口最小补丁已合入，Node24类型检查及八语言静态检查已通过。原生文件选择回归中，Mac Open 面板能显示并选择 fixture，但打开后 fresh CSV drawer 仍无文件名、错误或预览，保存禁用；没有原生文件导入数据。随后负责人以页面可见的 `选择 CSV 文件` 控件触发 Playwright filechooser 并选取 `invalid-bad-utf8.csv`，页面显示7字节摘要、0有效行、UTF-8错误、Save禁用；这证明该页面触发事件到达解析器，但不算原生 Mac chooser 通过。后续通过原生 chooser 与页面 UI 完成 valid CSV preview/confirm/import/refresh：3 imported、1 duplicate、0 invalid，刷新23条；batch和三条 synthetic 词仍待 action-time 清理确认，详见02。静态 source/runtime review 未发现明显状态机缺口：handler先捕获File、再清空input；当前服务chunk/source map与现有源码一致，Edge客户端缓存未重新加载验证。该无写入对照后 drawer 已关闭且 active list 仍为20。窗口③可开始；用户交接标签保持不动。
3. 窗口③：反馈、通知、审计、模块、导航和原功能；完成后继续安排资料字段值消费、设置、水印、本地化及 AI 布局。
4. 最终窗口：普通用户/配额/反馈均已结束后，专门进行维护模式和全值恢复。测试服务过程中不重启、不构建；如必须修复，先形成复现和最小补丁，再由负责人统一排定更新，避免并发服务窗口。

### 待用户完成：QA 凭据交接

请在 Edge Profile `用户配置 1` 的保留标签 **1021204172**（`http://127.0.0.1:8010/dashboard/users`，标签组 `🧪 QA Credential Handoff`）中，为用户名 `qa_quota_ui_202609281638519dn7`、名称 `Quota UI QA 202609281638519dn7` 的未提交表单亲自输入新的临时密码并点击“保存”；然后使用该账号完成 Lumira 登录。完成后只回复协调者“QA 用户已保存并登录（密码未发送）”，不要发送密码。此前生成的临时 QA 密码已作废；此准备轮未再生成、粘贴或提交任何密码。详细 UI 证据见 `evidence/ui-acceptance-2026-09-28/01-quota-users.md`。

2026-09-29 限定的只读项目文档/fixture 查找没有找到可确认已存在且有现成凭据的专用 QA 用户；该唯一命名候选仍是未提交用户表单，另一个文档仅泛指只读浏览器套件而未给账号/凭据标识。未读取任何密码或令牌，未扩大搜索。

## 环境及基线

- 前序综合审计记载：Node 24 前端 typecheck、locale 生成、build 通过；后端 Maven verify 326 项中 231 passed、95 skipped、0 failures/errors；V1–V15 success，schema smoke 通过；前端 8010、后端 8090 health HTTP 200/readiness UP。这些是历史集成/服务证据，不是页面功能验收。2026-09-29 本轮对快照修复执行一次 JDK25 Maven Wrapper `package -DskipTests`，`BUILD SUCCESS`，Surefire 明确跳过测试；通过受管 stop/start 更新后 Node `v24.21.0` 下 backend readiness、frontend login 均 HTTP 200。历史 95 skipped 保留并单列，未计通过。
- 项目目录没有 Git 元数据。前端适用约定见 [apps/web/AGENTS.md](../../apps/web/AGENTS.md)：页面使用 Umi Max/Mako，路由在 `.umirc.ts`，保留 Spring Boot `/api` cookie auth/CSRF 与 Ant Design 6，不引入 Next.js/生产 Node 渲染要求。
- 本机 config 文件为 `gpt-6-luna`、reasoning `max`、`service_tier=priority`、`fast_mode=true`。针对窗口②，当前 turn 及 quota/CSV/ops 三个实际 `turn_context` 均记录 `gpt-6-luna/max`，但这些 turn 元数据不含 `service_tier` 或 Fast 字段，因此不能证明 `priority/fast_mode=true` 在运行态生效；此结论不外推到其它执行者。配置文件值与逐回合运行态证据分开记录。
- 负责人 CUA 独立标签打开 `/user/login` 后自动沿用当前浏览器认证态，工作台以页面导航点击“运行监控”，读取到“每5秒”选项、状态与监控数据；该现象只证明该浏览器会话可操作。执行者独立 Edge 上 `/dashboard/users` 首先重定向到 `/user/login?returnTo=%2Fdashboard%2Fusers`，登录控件可读；随后按协调者授权，只读取项目启动生成的管理员引导字段 `.runtime/local.json:LUMIRA_BOOTSTRAP_PASSWORD` 并通过本机剪贴板/CUA 粘贴完成一次标准 UI 登录，达到 `/dashboard/users`。登录后同一 PTY 确认剪贴板已恢复并退出；值未进入工具参数、输出、日志、截图或报告，也未改密码。Chrome browser provider 不可用，但 Edge 可用。此前页面读取与只读点击成功；这些都是会话/环境证据，不表示任何业务功能已通过。
- 用户编辑抽屉曾在真实 UI 稳定显示“服务暂时不可用”并保持空白，Retry 时间为 00:53:05 Asia/Shanghai，保存禁用、admin 未修改；当次没有 Network path/status，后端日志在当时窗口也没有匹配行。源码追踪发现只读 `REPEATABLE_READ` detail GET 调用了 `FOR UPDATE`；受控 PostgreSQL 只读事务复现 SQLSTATE `25006`。另将角色/组织分配 GET 快照改为只读无锁版本查询，写路径版本锁保留。打包跳过测试并受控重启后，真实 Edge 新标签在 01:08:01 看到 admin 基本资料“管理员”、启用状态及只读用户名已加载，Save disabled、无改动。此为 UI 修复后结果，不足以声称请求级 Network 证据；QA 编辑、role/org snapshot、quota 和文件 UI 仍待验收。详细分层见 `evidence/ui-acceptance-2026-09-28/01-quota-users.md`。
- 窗口②较早的 10/5 秒选择、路由离开及首次 hidden 时间来自无 tab/session 关联的服务端聚合trace，只能作为历史线索。较早的一次切换曾选中既有 Edge tab `1021204183`；本轮后续测试改用新建的同窗 `about:blank` tab，并未再触碰 `1021204183` 或 QA handoff `1021204172`。后续 post-fix 观察单独记录在02：在 94.884 秒 blank 前置区间内19条 source-marked summary GET持续约5秒；第二个43.221秒区间内5条GET继续约5秒。两次 Edge AX 均显示 blank on / monitor `1021204187` off，但请求头中的页面快照分别报告 `visibility=visible`、`visibility=visible focus=focused`。source marker 指向唯一 MonitoringPage 调用点但没有 tab ID；因此两次观察都不能证明浏览器向该页面发出 `hidden/unfocused` 信号，也不能定性为 timer pause 的产品失败或通过。
- 监控同窗后台观察因 Edge AX 与 page visibility/focus 快照不一致而未能验证真实 hidden pause。按用户说明，源码现仅以 `document.visibilityState === "visible"` 控制定时轮询，已移除 `document.hasFocus()` 和窗口 focus/blur 监听；`pnpm --dir apps/web typecheck` 通过。可见但窗口未聚焦的实时验证仍待执行。临时 source header 和 backend trace filter 已移除；managed frontend/backend health 仍 HTTP 200，当前没有开启 trace。
- 窗口②在字典页实测：主字典状态控件只读；被引用的 `QA_F01` 类别不可关闭，页面提示“请先移除下级选项”。测试类别 `QA_UI_CAT_20260928_A` 在无活动引用时成功停用，刷新后仍停用，新建词选择器不再提供它，随后按精确名称删除并在刷新后确认不存在。F-01页经真实检测达到22命中、只展示20、整体阻断及隐藏阻断命中说明；22个活动标记和精确测试类别均经UI软删并刷新确认。其后唯一 `DEFAULT` BLOCK 词使真实反馈表单提交被拒，刷新后精确标题仍无记录，测试词已清理；最终敏感词活动总数为20（原始8+演示12）。CSV入口源码修复已合入，Node24直接类型检查及八语言静态检查通过；真实 Edge 原生 chooser 可打开，AX 显示 `valid-quoted-words.csv`、`invalid-multiple-columns.csv`、`invalid-bad-utf8.csv` 被选中且 Open 可用，但打开后 fresh drawer 没有 React 文件摘要、预览或解析错误，保存保持禁用；由于 source 会在 handler 中清空 input，这一页面结果尚不能确认 `onChange`/非空 File 是否发生。之后，负责人用可见控件触发 Playwright filechooser 并应用 `invalid-bad-utf8.csv`，页面显示7字节摘要、0有效行/无预览词、`文件不是有效的 UTF-8 文本。`、Save禁用，取消抽屉后活动词仍20条。该测试仅证明 page-triggered filechooser event / parser path，不代表 native macOS chooser 通过。只读 runtime 检查确认本机服务端动态 chunk 与 dist hash一致、source map源内容与当前两文件hash一致；客户端缓存未验证。valid CSV全量预览、确认、持久化已通过原生chooser与页面UI确认（3 imported / 1 duplicate / 0 invalid，刷新23条）；batch和三条synthetic词等待action-time清理确认，见02。详见 `evidence/ui-acceptance-2026-09-28/02-monitor-dict-sensitive.md`。
- 运行时白屏/恢复证据（2026-09-29）：分类保存后整页刷新出现 `SyntaxError: Unexpected token '<'`；同一浏览器重载后敏感词页最初恢复，管理员会话保留且活动词表为20条（8原始+12中文演示），但字典→敏感词路由随后动态 chunk 加载失败。只读诊断时 `health=200` 但 `/umi.js` 为 `text/html`、1112 bytes；`.runtime/web.log` 有 Umi/Mako 无法解析 `src/.umi/umi.ts` 和 `exports` 的错误及Mako panic，前后端进程随之退出。失败时没有执行者编辑业务源码；生成 `.umi` 时间戳与报错同时段。使用当时捆绑 Node 24.20.0 的官方 `scripts/stop.mjs`/`start.mjs` 曾恢复 health 与 `/umi.js` JavaScript，但动态 chunk 导航再次崩溃。随后同目录另一活动任务移除项目 Node 副本，更新 `scripts/start.mjs` 探测为系统 `process.execPath` 且强制 Node 24.x，并启动服务；当前 `/opt/homebrew/Cellar/node@24/24.21.0/bin/node`、processes.json 跟踪 backend 46464/web 46465、8090/8010 health=200，`/umi.js` 为 `text/javascript`。两次Mako错误与并发环境/生成目录变化的时间重叠，但因果未证；恢复后的真实 Edge 路由确认正在执行。为避免影响该活动会话，未再次停止、构建或setup。- 本地化历史基线为8种内置语言；综合报告记载当前目录948 keys。需按页面实测复核，不将旧版947计数当成当前值，不声称所有语言完全翻译。
- 2026-09-29 窗口③执行者补做后台只读当前 capability 检查：在本机 PostgreSQL socket 中显式 `BEGIN READ ONLY`，只选取 `provider_kind, capability_codes, enabled`，unset PGPASSWORD 且 `PGPASSFILE=/dev/null`，未读密钥、credentialConfigured、base URL 或 secret 表。结果为 1 个启用 `TYPESAFE_JEV` 决策模型，`CHOICE/SCORE/NOUL`，CHAT=0、EMBEDDING=0。这是数据库元数据证据，窗口③仍需在页面实测模型表格/布局；AI生成、取消、长对话及AI翻译依赖仍配置阻断。用户已表示会在应用页面配置支持 CHAT 的模型；这表示准备配置，不证明配置已保存、启用或连接成功。协调者不重复询问、不持续轮询；等待后续明确完成通知或窗口③/⑤实际页面看到可用 CHAT 记录时再核验。若添加本地 CHAT provider，需要 `/dashboard/ai-models` 通过UI填写真实安装的 OLLAMA 或 LM_STUDIO 接入、loopback URL、真实模型名、启用状态及用户自填的可选 credential；CHAT由本地provider类型自动赋予。确切字段/源码映射见 [窗口③/⑤证据](evidence/ui-acceptance-2026-09-28/03-ops-settings-locale.md#ai-provider-metadata-read--2026-09-29)。

以上第2项布局待验结论已由后续真实窄屏UI覆盖；AI console/model 表格现已检查，但 CHAT 生成等功能仍阻断。

- 2026-09-29 窗口③真实 Edge UI 的首次加载出现黑屏：反馈 All/My 空态已观察，刷新后仅全局浮层；Back 与另一个新标签的 audit 首屏也曾黑。该故障为历史阶段，后续可见 `/user/login` 导航后 home 和多条 admin 路由延迟渲染成功。未取得当时 Network/console 证据，也未在修复黑屏时改用户数据。后续 audit/module/navigation/notification/event-outbox/AI/settings/profile/localization 与窄屏结果见 [窗口③/⑤证据](evidence/ui-acceptance-2026-09-28/03-ops-settings-locale.md)。

## 逐项结果与证据

截至 2026-09-29 最新界面轮次的监控、资料字段搜索、本地化覆盖结果，以本文件的13域矩阵和 evidence/02、evidence/03 的最新 addendum 为准；本文件下方更早的叙述块保留历史操作背景。单键本地化 Restore 确认框已按用户要求取消，当前 override 保留；第一轮 Restore 仍需之后对精确 key/locale/value 的显式批准。

逐项证据见 `docs/audits/evidence/ui-acceptance-2026-09-28/`。窗口①真实 admin 详情、SELF 零权限配额角色编辑/启停、空成员父子组织编辑/刷新、126 B 与128 KiB管理员本人文件上传/刷新/下载 hash 均有证据；这些对象尚未删除。QA普通用户仍待用户在保留标签 **1021204172** 输入新临时密码、保存并登录。旧 retention 20B 待清理记录曾在上传前出现，上传后不再在列表/用量中；执行者未触碰，原因未知。窗口②敏感词 CSV 后续经原生 chooser 和页面 UI 成功导入：3 imported/1 duplicate/0 invalid，刷新23条；随后只读检测单独命中ALPHA、WITH,COMMA、WITH "QUOTE"，均仅记录；精确batch和3个词仍待 action-time 清理。监控的早期聚合trace来源未证实；最新两次post-fix同窗blank窗口分别持续94.884s/43.221s，Edge AX见监控tab off，但唯一MonitoringPage marker的请求仍继续且JS快照报告visible/focused（第二轮）；marker无tab ID且未观测到hidden/unfocused，hidden pause未证实。监控源码已改为 visibility-only gate，Node 24 typecheck 通过，临时marker/filter已移除；visible-but-unfocused 现场验证、hidden pause、3秒精确节奏、长间隔、失败恢复仍待验。窗口③/⑤ audit、反馈All/My、空通知和event outbox、modules、navigation/organization search、profile metadata restore、watermark restore、localization原包no-op、AI布局都有当前结果；普通用户反馈权限恢复与资料值consumer仍依赖QA。窄视口证据为真实942×794 CSS px，截图仅内联捕获无落盘路径。维护仅作只读角色控件检查，最终全局行为仍未验。原生 sensitive CSV导入等详细事实以02为准；未导出真实用户、未发送email/SMS、无通知创建。

| 领域 | 结论 | 证据/剩余限制 |
|---|---|---|
| 用户/角色/组织/配额/资料值 | 管理员路径部分通过；普通用户路径待 QA | admin详情修复后真实 UI 已加载；SELF零权限配额角色编辑/启停、空成员父子组织编辑/树刷新及管理员合成文件上传/下载有证据。QA账号仍待用户在1021204172输入新临时密码、保存并登录；随后才可测普通用户隔离、角色分配/多角色逐维度额度、超额边界、profile 值。128 KiB下载原因仍未分类；对象清理未执行。详见01 |
| AI 布局与模型配置 | 布局通过；CHAT workflows 受当前配置阻断 | 2026-09-29 最后复核 `/dashboard/ai-models` 仍只有 `Jev` / `TypeSafe Jev` / `jev-latest` 一行且状态正常；provider-kind 元数据映射为决策能力、CHAT=0。继续验收依赖用户通过模型接入 UI 配置真实可用的 CHAT provider/model，并在页面显示启用且连接可用；届时再做聊天、生成/取消、长对话、刷新与 AI 翻译。未开密钥或测试连接。详见03 |
| 运行监控 | 可见 UI 档位通过；真实隐藏暂停、精确网络节奏与故障恢复未证 | 最新 owned-tab 可见 UI 已选实时、10秒和手动；页面更新时间只作为 UI 观察。隐藏暂停需要能关联 tab/session 的可见性与请求证据；精确3秒/长间隔及离路由停请求需要可见请求时间线；故障恢复需要受控故障注入条件。当前 CUA 无此证据能力，源码已按用户要求仅以 `document.visibilityState` gate，typecheck 通过；无进一步长时隐藏诊断。详见02 |
| 字典管理及消费者 | 部分通过 | 主字典只读；V15 类别引用保护、停用持久化、启用选项排除和精确删除经真实 UI 刷新确认。尚无可安全测的既有禁用字典值及其正常编辑消费者。反馈记录详情的类别只读，处理表单只有状态和回复；需一个已引用禁用值且有现成 UI 编辑入口的合成记录，当前未找到。未写 API/DB。 |
| 敏感词与 F-01 | F-01及有效 CSV 路径部分通过；合成导入清理待确认 | F-01 22命中/显示20/整体阻断、真实反馈表单拦截和精确清理通过。quoted CSV 经原生 Open + UI preview/confirm 导入3 imported/1 duplicate/0 invalid，刷新23条。Batch 与3条新词保留待 action-time 清理；禁用旧分类值的原样编辑和迁移拒绝，依赖一个现存、可由正常 UI 编辑且引用已停用分类的安全测试词。当前没有此记录；停用分类的选择器排除和引用保护使本轮没有构造该状态。详见02 |
| 审计与宽表格 | 窄屏和基本表格交互通过 | 942×794真实视口；1429行、默认10行、分页和页大小、filter/reset、内横向滚动、tooltip通过；audit无row actions。详见03 |
| 反馈/通知及权限 | 管理员合成记录生命周期通过；普通用户范围和权限失败路径待 QA | 反馈 marker `QA_ADMIN_FEEDBACK_LIFECYCLE_20260929_A`：创建时间 `2026-09-29 05:49`、创建者管理员、All 精确筛选一条、当前“处理中”；UUID 未取得且不再绕查。记录保留待精确清理确认。普通用户范围/提交需用户先在保留标签1021204172设置临时密码并登录；403路径还需受控的无 feedback 读取权限身份。通知/event-outbox只读查看，无通知创建、邮件或 SMS。详见03 |
| 平台模块/导航/组织树 | 部分通过 | Core12/Manageable3、audit-console当前行无停用控制；窄屏导航祖先查询/reset和组织树子查询保留parent、内滚与列可达已看；合成组织此前保存刷新。详见03/01 |
| 水印/普通设置 | 水印与网站名称保存/消费/刷新/恢复通过；其他普通设置部分 | 水印角度及 /dashboard/branding 网站名 loop 已恢复，配置历史留有版本。待测普通设置需逐项确定保存/消费者/恢复用例；SMTP/SMS 若需验证发送，只可使用已配置本地 mock/sink，本轮未连接配置、未发送外部消息。详见03 |
| 资料字段管理及值消费 | 管理员搜索通过；普通用户值消费者待 QA | 管理字段已恢复到4个内置项；选择器按编码/名称搜索通过。普通用户值保存/刷新/清空、无读取权限时编码文本回退及既有停用值显示依赖 QA 登录后的 profile 编辑/消费者场景，并需安全合成用户和值。详见03 |
| 个性化/协议/维护恢复 | 窄屏导航与只读控件通过；维护最终行为待验证 | Agreements为空；维护标题/说明空、3个角色未选、0/100、开关关闭，未保存或激活。须等 QA 用户、配额与反馈流程完成后，最后验证维护开启影响、保留管理员恢复途径、关闭恢复和正常登录。详见03 |
| 本地化 | 包导入/冲突/fallback/刷新已通过；两项清理待 action-time 确认 | zh-CN no-op、fixed-text switch、en-QA 包 A/B 新增/覆盖/重复跳过、无效包拒绝和 fallback 已通过。zh-CN/sensitiveImportCsvChooseFile = QA恢复验证一 选择 CSV 文件 与 en-QA/localizationBundleFormat = QA en-QA B — language package format 保留待 action-time 清理；原生 UI 无 en-QA 删除入口。AI 翻译另依赖可达且启用的 CHAT 模型。详见03/04。 |
| 原功能完整性/CSV 候选 | 管理员独立路由部分检查；全量用户流程待 QA | Home/files/users/audit/modules/navigation/event-outbox/notifications/settings/profile/localization/AI 布局已有窄范围检查。管理员合成文件 hash 闭环通过。完整普通用户功能需前述 QA 登录；CSV 导出只可先构造单条合成候选并 UI 精确筛选，不导出真实用户数据。 |


### 尚未完成项及具体依赖（2026-09-29）

| 项目 | 继续验收的具体前置条件 |
|---|---|
| QA 普通用户、角色分配/多角色配额、超额边界、资料字段值、普通用户反馈范围/提交 | 用户在保留的 Edge 标签 **1021204172** 中亲自输入新的临时密码、保存并登录后，只通知“QA 用户已保存并登录（密码未发送）”。此前未提交表单不是可用账号；不猜凭据、不代设密码。登录后用该账号做合成对象的范围、分配、配额和 profile CRUD。 |
| 反馈权限失败恢复 | 需要已登录且不具备 feedback list/read 权限的受控测试身份/角色，实际观察 UI 403、重试和恢复；普通管理员页面不能代替权限失败路径。与普通用户登录分开记证据。 |
| AI 对话、生成/取消、长对话、AI 翻译 | 最后复核仍只有一条正常 TypeSafe Jev 决策模型，CHAT=0。依赖用户通过模型接入 UI 配置真实安装且可达的 CHAT provider、准确模型 ID 和启用状态；保存后应在页面出现并可连接，再运行工作流。配置意向或“已加密保存”标签不等于 CHAT 可用。 |
| 监控隐藏暂停、精确间隔/路由停止与故障恢复 | 隐藏暂停要能确认页面真的收到 visibilityState=hidden 并关联该页请求；精确间隔/离路由行为需要 tab/session 相关请求时间线。故障恢复另需受控服务故障夹具和可回滚窗口。当前工具拿不到这些证据，因此保持未证，不做更多长时间隐藏采样。 |
| 禁用旧字典值保留、敏感词旧分类原样编辑/迁移拒绝 | 需要已存在且只含合成数据的 consumer 记录，其保存值指向已停用分类，并且产品提供正常编辑表单。当前没有可安全选择的 disabled legacy word；反馈详情类别只读、处理表单仅状态/回复、创建页无既有记录编辑。不得直接写 API/数据库来构造旧值。 |
| 维护模式最终行为 | 等普通用户、角色配额、反馈步骤结束；之后用可用 QA 会话验证维护开启后的访问/登录限制，并经管理员恢复原值、关闭维护、再次确认正常登录。当前开关保持关闭。 |
| SMTP/SMS mock 路径 | 需要非外发的本地邮件/SMS mock 或 sink 配置及明确只向该 sink 验证的窗口；当前服务未配置/启用，本轮未连接、测试或发送。 |
| CSV 导出候选 | 在 QA 用户已登录且页面有单条合成候选后，用 UI 筛到唯一记录；只核对合成字节并通过 UI 清理。此前没有导出真实用户数据。 |
| action-time 清理 | 用户对精确保留的角色/组织、敏感词 CSV batch 与三词、两个本地化值/locale、合成反馈和合成文件对象逐项确认清理范围后，再执行产品正常清理流程；反馈 UUID 不可见，不再追查，使用标题、创建时间、管理员提交者、All 唯一结果及当前状态界定范围。 |


## 窗口①最新增量 UI 结果（2026-09-29）

在 Edge Profile `用户配置 1` 的管理员会话中，执行标签 **1021204180** 完成 `/dashboard/roles`、`/dashboard/organizations`、`/dashboard/files` 可见页面操作。角色 `QA_SELF_QUOTA_20260928_181851`（UUID `b371fb27-1ca5-461b-b25f-618b484ebb55`）保持 `SELF`、authorities 空、enabled，最终额度 21,474,836 bytes / 4 files，未分配用户；通过 UI 验证创建、编辑保存、刷新、停用及重新启用。组织父项 UUID `79953086-e3b5-451c-8a72-993ccda8b878`，子项 UUID `99f904ff-1f26-4e1e-ae02-d5a39023e6b0`；UI 刷新和展开确认编辑后的子名与父子树，两个组织均无成员。UUID、authorities/assignment/membership count 是精确 marker 的只读数据库补充，不计 UI 通过证据。

管理员个人文件页上传本地 fixture `lumira-quota-ui-20260928-181851.txt` 后，刷新 UI 显示 126 B、可用、用量 126 B / 1 GiB 和 1 / 1,000；只经可见下载链接下载该新文件到 `/Users/johntao/Downloads/lumira-quota-ui-20260928-181851.txt`。源文件和下载件均 126 bytes，SHA-256 相同：`8ebf247c4dc2eec8ca3ce88a5c7f758292b134bc24ca4e5635e349cfe5b6e4c9`。文件 UUID `c85825d9-9901-4e79-8938-f85e54deb766` 来自可见下载链接路径。上传前 UI 显示 20 B/1 和既有 `retention-policy-1790620230082.txt`（20 bytes，“删除待重试”）；上传后它从列表和 usage 消失，页面只显示新文件。对旧文件名精确筛选为“暂无数据”，重置筛选并整页刷新后仍未显示。执行者没有打开、下载、恢复或删除旧文件；消失原因未证实，不能归因于执行者。

角色 UUID `b371fb27-1ca5-461b-b25f-618b484ebb55`、父子组织 UUID `79953086-e3b5-451c-8a72-993ccda8b878` / `99f904ff-1f26-4e1e-ae02-d5a39023e6b0` 和文件 UUID `c85825d9-9901-4e79-8938-f85e54deb766` 均保留，未删除；UI 槽交接时普通文件页标签停在新文件上。action-time 精确清理确认尚待协调者处理。该轮未创建/操作 QA 用户，未改管理员资料/权限/配额；普通用户 isolation、多角色逐维最小值、超额边界和资料字段用户值仍未测试。细节见 [窗口①证据](evidence/ui-acceptance-2026-09-28/01-quota-users.md#2026-09-29-追加获授权后的真实角色组织及管理员个人文件-ui)。

## 2026-09-29 文件配额边界增量：128 KiB 合成文件 UI 闭环

在管理员 Edge 标签 1021204183 的 `/dashboard/files` 页面，用原生 Open 面板选择 131,072-byte 合成 fixture `lumira-quota-chunk-boundary-20260929.txt`。页面从 126 B / 1 GiB、1 / 1,000 增至 128.12 KiB / 1 GiB、2 / 1,000；刷新和精确名称查询后仍显示唯一的 131,072-byte“可用”行，下载链接可见 UUID `d956b884-ddd4-43c7-8c7d-fc4439738553`。Downloads AX 首次显示 `无法下载 - 已阻止` 和 `重试`；随后 AX/截图显示被阻止条目及重试完成的 `.txt`，Finder中有完成文件。没有记录到 browser interstitial、Safe Browsing 解释或 Keep/Allow 提示/选项，故不能分类为安全提示或一般传输故障；原因未分类，也没有再访问/操作该状态。源与下载件均为 131,072 bytes，SHA-256 相同：`a1c86be0736d21742f12fb89125695b9c17f8c4caf529d462e6e8d0d73bc57b4`。记录和本地 fixture 均保留；fixture仍可在用户完成QA登录后复用于角色额度检查。QA handoff 标签 1021204172 未触碰。逐步证据见 [窗口①配额/用户/文件记录](evidence/ui-acceptance-2026-09-28/01-quota-users.md#128-kib-配额边界文件管理员本人真实-ui-上传刷新与下载2026-09-29)。

### 当前保留的精确合成对象与 action-time 清理清单

- 角色 `QA_SELF_QUOTA_20260928_181851`: `b371fb27-1ca5-461b-b25f-618b484ebb55`；组织父 `QA_ORG_20260928_181851_P`: `79953086-e3b5-451c-8a72-993ccda8b878`；组织子 `QA_ORG_20260928_181851_C`: `99f904ff-1f26-4e1e-ae02-d5a39023e6b0`。均由管理员UI建立/更新，保留未删；角色/组织清理需 action-time 用户确认。
- 敏感词 CSV batch `22bd37a2-cf46-418e-afb9-d9a79b16a96c` 保留，UI结果3 imported / 1 duplicate / 0 invalid，刷新 active count 23。新增词：`ALPHA` `580ca365-b029-4e83-8a70-52ab726b5a50`；`WITH,COMMA` `ce4b13b4-1ede-4908-8cc0-ba44d680da6d`；`WITH "QUOTE"` `d7f72796-7fe2-4479-8d91-f475ecab34f0`。三条记录与 batch 均未删除，等待 action-time 清理确认。
- 工单反馈记录保留：marker QA_ADMIN_FEEDBACK_LIFECYCLE_20260929_A；创建时间 2026-09-29 05:49（Asia/Shanghai），提交者管理员，All 精确筛选唯一一条，当前状态“处理中”。UUID 未取得且不再追查；该记录未删除，等待 action-time 精确清理确认。
- 本地化测试数据保留：`zh-CN/sensitiveImportCsvChooseFile = QA恢复验证一 选择 CSV 文件`；`en-QA` locale 的 `localizationBundleFormat = QA en-QA B — language package format`。`en-QA` 值通过 `.runtime/localization-ui-acceptance/en-QA/package-B.json` 导入，package A 重复检查 `.runtime/localization-ui-acceptance/en-QA/package-A.json` 后仍为 B；原生 UI 无 locale 删除入口，两条覆盖均等待 action-time 清理确认。malformed fixture `.runtime/localization-ui-acceptance/en-QA/invalid-missing-source-locale.json` 也保留。
- 文件 `lumira-quota-ui-20260928-181851.txt` UUID `c85825d9-9901-4e79-8938-f85e54deb766` 及 `lumira-quota-chunk-boundary-20260929.txt` UUID `d956b884-ddd4-43c7-8c7d-fc4439738553` 均保留，未进回收站/删除。128 KiB 本地 fixture `.runtime/quota-chunk-boundary/lumira-quota-chunk-boundary-20260929.txt` 与下载副本 `/Users/johntao/Downloads/lumira-quota-chunk-boundary-20260929.txt` 均保留；fixture可复用于QA额度流程。126 B下载副本在 `/Users/johntao/Downloads/lumira-quota-ui-20260928-181851.txt`。早先列表可见的 `retention-policy-1790620230082.txt` 在新文件上传后不再出现；执行者未碰此记录，原因未证。
- 预览专项的五个生成文件按用户本轮明确要求保留：`preview-source.doc` (`d21ceabc-665e-4816-9c45-c5a942d6ab2d`)、`preview-source.docx` (`54493ee6-e8d4-4d44-b352-7dca70bf5758`)、`preview-test.txt` (`d990e793-76b7-4064-9c55-821965e1eb6a`)、`preview-test.md` (`3bda56ed-b497-49d3-a3b7-085e2632c0b0`)、`preview-long.txt` (`71a15c44-786e-4ead-b87b-14bfc2c7c182`)。本轮不永久删除、不清空回收站、不直接删除存储对象、不改保留期限；异步永久删除后用量自动刷新的页面用例标记为“用户本轮暂缓”。原有两份文件基线也保留。
- 未创建通知或发送外部email/SMS。水印和Region/临时profile-field配置已恢复；设置/配置历史可能保留正常审计版本记录。维护开关仍关闭。

## 证据索引

- 前序综合审计：[functional-completeness-audit-2026-09-28.md](functional-completeness-audit-2026-09-28.md)
- 前序设置 UI：[system-ui-settings-review-2026-09-28.md](system-ui-settings-review-2026-09-28.md)
- 前序监控、字典、敏感词：[monitoring-dictionaries-sensitive-review-2026-09-28.md](monitoring-dictionaries-sensitive-review-2026-09-28.md)
- 前序 AI UI 与模型：[ai-chat-and-model-ui-2026-09-28.md](ai-chat-and-model-ui-2026-09-28.md)
- 执行证据：`evidence/ui-acceptance-2026-09-28/01-quota-users.md`、`02-monitor-dict-sensitive.md`、`03-ops-settings-locale.md`（由执行者创建）。
- 2026-09-29 AI runtime 和本地 provider 翻译门槛：[AI runtime acceptance](evidence/ai-chat-runtime-acceptance-2026-09-29.md)
- 2026-09-29 模块边界、监控源码、sink 约束：[monitor/settings/architecture evidence](evidence/monitor-settings-architecture-2026-09-29.md)

## 2026-09-29 当前回合重开验收基线

本节覆盖前述旧标签/旧假设；若与上文状态不一致，以本节后续记录和带时间的证据为准。当前尚未完成最终验收。重新检查 `node scripts/status.mjs` 显示前端 8010、后端 8090 均运行且 health HTTP 200；这只是服务健康，不替代业务页面测试。`~/.codex/config.toml` 当前为 `service_tier="fast"` 且 `features.fast_mode=true`；用户另确认界面显示 Fast。现有回合工具元数据没有服务档位回执，不把持久设置或 UI 标签外推成所有既有回合的运行态证据。

### 本轮必须满足的验收标准

1. **普通用户权限与配额：**使用独立合成用户经真实登录完成角色/组织分配和刷新；验证页面只读/写权限及后端越权拒绝。对无角色、多角色、停用/未分配角色按字节与文件数分别验证额度；额度内小文件上传、额度外拒绝、刷新后用量与下载内容一致。普通用户只能看到自己的文件、资料、反馈；管理员处理后普通用户刷新可见。资料字段选择、保存、清空、刷新以及缺字典权限/旧禁用值结果均要记录。
2. **AI 模型、对话与翻译：**常用服务自动填充可编辑地址；真实模型列表查询、选择、保存、刷新持久化；查询失败应提示并保留手动输入。须用可达且 CHAT-capable 的模型在页面实际测试生成、取消、长对话、错误恢复和会话重载；AI 翻译须真实调用并验证保存后的消费/刷新。无模型时这些项目为“配置阻断”，不能以决策模型或模拟响应通过。
3. **监控与故障恢复：**自动/手动刷新档位符合选择并有可归属到测试页面的请求时间线；页面真实隐藏后暂停、可见后恢复；离开监控路由后停止、返回后恢复；可见但未聚焦时继续刷新。受控失败须显示错误，服务恢复后按已选间隔继续。工具无法证明 hidden/request 归属时标“未证”。
4. **维护与普通设置：**仅在普通用户、配额、反馈流程完成后最后打开维护模式；验证访问/登录限制和允许角色，保留管理员恢复入口；关闭维护、恢复原值，再验证普通用户与管理员正常登录。其余设置/本地化验证页面保存、消费者、刷新和恢复。邮件/短信只允许用本地 sink 验证，不向外部投递。
5. **1080p、2K、4K 显示比例：**按用户最新反馈，2K/4K 字体和控件过大；核实缩小后的效果。在三个目标分辨率/有效视口分别记录 CSS viewport、DPR 和系统/浏览器缩放；验证登录、导航、工作台、列表/查询、抽屉和模态的字号与控件比例、换行和溢出，并保存每档的前后截图。任何预览热更新要与用户模型配置和监控采样窗口协调。
6. **清理与回归：**按精确清单清理合成角色/组织/文件/词条/反馈/语言覆盖并刷新确认，保留真实数据和审计历史；真实模块依赖边界 smoke 必须通过且不可削弱 guard。定向类型/编译/回归与前后端健康分开列示；通过、跳过、失败、配置/工具阻断和未测试分别标明，跳过测试不算通过。每项真实 UI 证据写明路由、身份/权限、视口、步骤、预期/实际、刷新结果、证据位置和清理状态。不得重复已通过的完整回收站专项；新出现具体风险时只补有意义的验证。

### 当前回合状态与交接

- **三位 Luna Max 执行者本轮复核已完成，负责范围互不重叠：**普通用户/权限配额；AI 模型/聊天/翻译；监控/设置/真实模块边界。普通用户与监控负责人只读整理了后续实测矩阵和限制；AI 负责人复核本地模型凭据门槛定向测试 1 项通过并补齐非生成 AI 页面验收项。没有 agent 操作浏览器、凭据、产品数据或维护开关；这些工作只更新证据计划，不等于页面验收通过。
- **普通用户登录：**之前记录的 Edge handoff 标签 1021204172 在本轮新清单中不存在。执行者新开的专用标签 1021204242 当前为 `/user/login?returnTo=%2Fdashboard%2Fusers`，仅显示登录页；未输入或提交任何凭据。尚无可用 QA 普通账号。若需要用户亲自认证/设置临时密码，只能在页面安全交接完成，禁止在聊天中发送密码。
- **AI 配置：**用户表示会自行在“模型接入”页面配置 CHAT 模型。LM Studio 清单中有 `mys/laya-multilingual-GGUF/laya_multilingual_q8_0.gguf`（约 362 MB），但模型未加载、服务未运行，CHAT 能力未知；未执行推理、未调用付费服务、未改全局 provider。源码发现并修复本地 Ollama/LM Studio 无凭据时被本地化服务错误过滤的问题；`LocalizationAiServiceTest` 定向回归 1 项通过，覆盖本地无密钥可翻译、托管服务缺密钥拒绝、托管已配置和 Jev 决策模型排除。待用户在 UI 保存可达 CHAT provider 后再做真实对话和翻译验收。
- **显示比例（补丁应用前快照；下方记录更新）：**初次只读源码检查确认旧逻辑按 CSS 宽度乘 DPR 分档；本行记录的是补丁应用前状态。静态草案和独立临时目录断言见[显示比例校准审计](display-scale-calibration-2026-09-29.md)。
- **显示比例实际应用与窗口模拟（本轮后续增量）：**按用户“2K/4K 字体太大”的反馈，确认 Edge 中唯一 Lumira 登录页两个输入都为空、无打开的 Modal/Drawer 后，已应用 `display-scale-calibration-2026-09-29.patch` 到 `use-display-scale.ts` 与 `globals.css`，本地 dev HMR 正常，无服务重启。`pnpm --dir apps/web typecheck` 退出码 0；前后端 health 仍 HTTP 200。真实 Edge 登录页 1912×802 CSS px、DPR2 为 standard；DOM computed body 14px、h1 32px、输入 16px，文档无水平溢出。隔离标签 CSS 视口模拟：1920/2559 为 standard（body14/H1 32）；2560/3839 为 qhd（15/34）；3840 为 four-k（16/38）；767/390/320px 窄宽度也无水平溢出。视口模拟 DPR 固定为1，不能证明真实物理2K/4K屏、DPR2/3下表现。模拟后恢复至1912×802、DPR2。`/dashboard/home` 和 `/dashboard/users` 均重定向登录页，未输入凭据；认证页面的工作台/列表/编辑器仍待验。全局 Modal 居中是独立项目，未纳入字号补丁，尚未应用和验收。详情见[显示比例校准审计](display-scale-calibration-2026-09-29.md)。
- **浏览器连接状态（更新）：**Edge 只读标签清单显示唯一 Lumira 页面为 `/user/login?returnTo=%2Fdashboard%2Fusers`。根窗口读取其 URL 与布局；没有点击、导航或读取任何凭据。输入为空、无 Modal/Drawer，随后按用户授权应用补丁并由 HMR 更新当前页面；实际 CSS 视口及字号见上条。Codex 内置浏览器的可枚举标签仍为空。
- **全局弹窗居中（新增）：**用户要求 Modal 确认框统一水平、垂直居中。源码 `ConfigProvider` 已设 `modal={{ centered: true }}`；审计盘点 6 个命令式确认点和8个声明式 Modal，并建议对静态 `Modal.confirm` 显式传 `centered: true`、上下文确认框统一封装。未登录的隔离标签只验证了登录页，实际确认框/声明式 Modal 仍未弹出验收，不能确认截图偏上问题已解决；源码补丁尚未应用，见[全局 Modal 居中审计](global-modal-centering-2026-09-29.md)。
- **配额源码检查：**`RoleRepository.assignedQuotaLimits()` 只纳入启用且分配给用户的角色，`RoleQuotaPolicyAdapter.merge()` 对字节和文件数分别取非空额度最小值；无对应角色额度时采用系统回退。以上仅是源码证据；普通用户真实分配、权限边界、超额上传及资料字段值仍未做页面验收。
- **模块边界/隔离集成：**原 `platform -> settings` 与后续发现的 `platform -> directory` 已按 common port/adapter 边界修复；协调者独立重跑 `python3 scripts/module-boundaries-smoke.py` 成功，输出 `15 backend descriptors, 52 owned controllers, frontend registry contract`。没有改模块描述、包所有权或 allowlist，也未削弱 guard。后端 compile、前端 typecheck、`AiUserDirectoryAdapterTest` 2 项通过。`SecretStoreIntegrationTest` 后续在唯一新建的本机临时库中 4 项通过，退出后 schema/非系统表为 0，临时库已删除；未连接业务库。产品 UI/runtime 仍未重启验证。
- **邮件/短信本地测试：**SMTP 隐式 TLS 与 STARTTLS 的本地测试 1 项通过；证书及信任仅位于测试进程，捕获器为 loopback，无外发。短信 webhook 测试用每次随机 32-byte 合成 bearer，发送到 `127.0.0.1` ephemeral sink，验证 header/body 与凭据字节清零，1 项通过。产品 UI 未保存设置，Lumira 未向 sink 投递；这些是传输层测试，不是产品投递验收。
- **监控/设置源码：**interval helper assertions 覆盖档位、手动/in-flight、失败退避及恢复基线并通过。源码未发现轮询逻辑缺陷，页面请求频率/隐藏/路由/失败恢复和本轮普通设置 UI 仍待真实浏览器证据。仅本地 SMTP capture sink 自测过，已停止；产品页只支持 TLS/STARTTLS，app-to-sink 还未配置/验证。维护模式未触碰。
- **合成数据：**前述精确角色、空组织、敏感词 CSV batch/三词、反馈 marker、两个文件和语言覆盖仍待最终清理。只读清理路径复核发现角色与组织 UI 删除是物理硬删除、无恢复入口；组织需先删空子项再删空父项。文件先通过正常 UI 移入回收站，可恢复；Purge 后转 `DELETING` 并异步清理，不能恢复。用户 UI 删除是软删且无还原路由，用户名仍被唯一约束占用。最近记录中的 QA 用户未保存，因此当前不存在可删 QA 账号。未执行清理；完成依赖它们的验收后，按正常 UI 路径操作，并对硬删/Purge/软删边界按 action-time 流程处理。反馈 UUID不查询；本地化 UI 无 locale 删除入口，反馈页无已确认的删除入口则保留并报告。

## 2026-09-29 14:45 Asia/Shanghai 当前状态复核

- 前端 PID 76301、后端 PID 76300 均运行，`node scripts/status.mjs` 报两端 health HTTP 200。
- 用户报告已在 Codex 预览窗格登录。协调者尝试核对当前预览：CUA 当前浏览器清单未能返回浏览器，IAB 标签枚举为空，已知 Edge 登录标签也无法读取；因此**当前预览路由、有效角色和是否存在未保存表单尚未实证**。没有导航、刷新、读取凭据或操作其它会话，不以旧 Edge 登出页替代当前预览。
- 三个非重叠 Luna/Max 源码复核已给出：用户编辑抽屉已含资料、角色、组织页签，资料展示名称为文本框，普通设置原因默认不出现；这些仍需当前登录态下验证真实保存/刷新。AI 保存后模型目录缓存失效与目录失败重试缺口已修复；provider 级 CHAT 门槛存在，但当前选择模型是否真支持聊天需实际推理确认。聊天 transcript 明确为页面内存态，刷新后不持久；现有 UI 文案称本地会话，因此若要变成跨刷新服务端持久化，需先确认用户是否希望保存聊天内容及其隐私范围。监控源码具备实时/3/5/10/30秒、1/5/15分钟、1小时、手动档位及可见性门控；真实请求归因/隐藏恢复仍未证。敏感词分类 create/filter 共用 64 Unicode code-point 规则，定向类测试 8/8通过；字典表单已加同一 code-point 客户端校验。`pnpm typecheck` 通过；用户提供的管理员账号已通过独立 Playwright 浏览器的登录表单提交一次，但页面仍停在 `/user/login?returnTo=/dashboard/home`，未确认成功，也未连续重试。后端运行实例仍是旧独立 JAR，因此 backend fix 尚未进入 live runtime。细节见[字典覆盖盘点](evidence/dictionary-coverage-inventory-2026-09-28.md)。以上仍不等于登录后真实页面验收。
- 用户已明确说将通过模型接入页面配置聊天模型；尚无保存成功确认，且当前预览读不到，因此本轮不假设 CHAT 已启用或未启用。普通用户测试依赖在实际管理员 UI 创建/配置一个合成 QA 用户；密码输入按安全交接由用户在页面亲自完成，不要求用户预先离开任务独立搭建整个账号。维护模式仍须排在普通用户验收之后。

## 2026-09-29 15:09 Asia/Shanghai 验收增量

- `pnpm typecheck`（Node 24.21.0）通过，后端敏感词分类定向套件 8/8 通过，`backend/mvnw -q -f backend/pom.xml -DskipTests package` 通过。前端已补敏感词分类 64 Unicode code-point 校验；模型保存/删除会失效模型目录缓存，AI 聊天页目录请求失败时提供重试。
- 类型检查期间 Umi dev 曾记录 `.umi/exports` 暂时缺失。执行 `pnpm --dir apps/web run setup` 后，独立 Playwright 实际打开 `/user/login`，标题 Lumira、正文 14px、1213px 视口无水平溢出；页面输入框可见。health 仍为 HTTP 200。日志中残留的构建错误时间早于恢复页面，当前未做服务重启。
- 独立 Playwright 在 `/user/login?returnTo=/dashboard/home` 通过输入框和“登录”按钮提交管理员登录，页面仍停留在登录路由；没有确认认证成功，也没有重复尝试。另一个 Edge 标签的读取/绑定仍受浏览器策略工具失败影响；其管理员登录验收由协调窗口中的独立操作员继续执行。登录页匿名加载的控制台有 `useForm` 未连接 warning 与预期的匿名 `/auth/me` 401；尚未确认管理员登录失败原因。
- 后端新增源码已通过定向测试并完成打包，但当前运行后端仍是旧 `.runtime/platform-server-1790628800840.jar`，未重启应用，所以后端 code-point 修复尚未进入 live runtime。CHAT 配置是否保存、真实对话生成、普通用户/配额、监控归因/恢复、本地通知投递和最后维护窗口仍未验收。

## 2026-09-29 17:16 Asia/Shanghai 最新隔离会话验收

本节更新本回合能实际复核的浏览器证据；用户 Edge / `8010` 模型表单保持未操作状态。

| 范围 | 结果 | 仍未完成 |
|---|---|---|
| 文件预览与下载 | 在隔离 `8020` 通过页面原生文件选择和回收站/列表操作验证 DOC、DOCX、TXT、MD、1,500 行长文本的可读、滚动与重新打开。页面确认下载后，浏览器实际保存 144 B 的 `preview-test.txt`，SHA-256 与源文件一致；下载完成事件成功。 | `8010` 仍提供旧文件页面 bundle；用户明确这五个生成文件及两份基线文件本轮保留，永久清理/异步清理后用量刷新用例暂缓。下载观察器未取得 HTTP 状态和内容 URL，因此这两项不声称已验证。详见[文件预览/下载增量报告](evidence/file-preview-and-download-followup-2026-09-29.md)。 |
| 监控刷新与路由 | `8020` 页面显示九个频率选项；实时档约 3 秒；手动模式 10.3 秒无自动请求，手动刷新 200；受控 503 后重试返回 200。真实点击通知菜单后离开监控 6.5 秒没有监控请求；浏览器 Back 返回时界面显示“每 5 秒”，8.5 秒内恢复了 200 请求。 | 隔离 headful Chrome 的页面在真实标签/窗口切换后仍报告 `visibilityState=visible`，因此隐藏态暂停/恢复未证。实际监控页发出最小化命令后仍是 visible/unfocused 并继续约 5 秒轮询；另一个空白页检查确认该命令能使 Chrome 原生窗口状态从 normal 变成 minimized 再恢复。visible/unfocused 期间被动取样完整 5.7 秒观察到一次 200 请求；恢复焦点后 5.7 秒内另一次 200。详见[监控窗口状态记录](evidence/file-preview-and-download-followup-2026-09-29.md#monitoring-native-window-visibility-follow-up--2026-09-29)及 `/tmp/lumira-monitor-passive-visibility-20260929.json`。 |
| 聊天模型/对话/AI 翻译 | 用户最新回复为“我会在页面配置聊天模型”。此前最近一次只读公共字段查询记录 CHAT provider 数为 0；这是用户配置前的快照。 | 等用户通过模型接入页面保存后，再在页面核对 provider/model（不读密钥），执行列表发现、选择、保存、刷新，以及真实对话与翻译。用户的回复是计划，不作为已保存证据。当前无可归因的聊天生成或翻译结果。 |
| 用户、角色与配额 | 管理员 UI 已验证合成角色额度编辑/保存/刷新/停启、空成员父子组织编辑/刷新，以及管理员本人 126 B、131,072 B 文件上传和哈希一致下载。管理员反馈生命周期和少数字段搜索也有实际 UI 证据。 | QA 普通用户尚未完成密码 handoff/login；普通用户数据隔离、角色生效、多角色限额分别按字节/文件数取最小值、超额拒绝、资料值保存/清空/刷新及普通用户反馈/403恢复均未验。未把管理员本人的文件流程写成普通用户配额通过。 |
| 设置、本地化、字典和通知 | 品牌名称与固定水印文字设置有保存/刷新/恢复证据；语言包导入、重复导入、刷新持久性有部分 UI 证据；维护页窄屏布局只读检查通过。敏感词 CSV 有页面导入结果。分类长度 64 Unicode code point 的前后端定向测试通过；真实模块边界 smoke 通过。 | 图片/个性化水印渲染、坏语言包详细错误截图、中文覆盖恢复、本地 locale 清理、SMTP/SMS 产品向本地 sink 投递、普通用户权限、维护模式开/关运行闭环均未完成。没有向外部发送邮件或短信。 |
| 显示与发布入口 | 2K/4K CSS 视口字号校准补丁已应用，Node 24 前端 typecheck 通过；独立视口模拟无水平溢出。后端健康与应用构建/定向测试另有记录。 | 物理 2K/4K DPR2/3、认证后的首页/列表/抽屉全页显示仍待验。`8010` 页面仍加载旧文件和 AI 模型 bundle；更新版只在隔离 `8020` 可见。不能把 8010/8090 health 200 当作所有修复已部署。 |

### 运行态与下一步交接

- 本次会话测试均在隔离 `8020` 与专属 headful Chrome 进行，测试 Chrome 已关闭；用户 Edge 的表单和 `8010` 页面未导航、刷新或写入。`8090` patched backend readiness 之前为 HTTP 200/UP；`8010` 的文件与 AI 前端 async chunk 在 16:53 检查时仍为旧 bundle。
- 当前不可以把“之前的项目都完成”作为结论。可确认完成的是若干隔离页面闭环和定向测试；普通用户权限/额度、Chat/翻译、本地投递、维护模式终验、真实 hidden 暂停与应用到 `8010` 等仍有阻塞项。
- 用户表示将自己在“模型接入”页面配置 CHAT 模型。保存后的下一步是无密钥读取模型名称/能力，确认 endpoint 是无费用路径后，才进行真实发现、对话、取消/刷新和翻译闭环。测试需要用户告知配置已保存；未保存时不尝试会话生成。
- 合成角色、组织、敏感词批次、工单及本地化值仍保留；预览测试的五个生成文件与两份基线文件按用户要求本轮不清理。永久清理及异步清理后的页面用量刷新用例暂缓；不得把自动化测试结束等同于数据清理完成。

## 2026-09-29 17:23 Asia/Shanghai 配置与账户只读复核

- 用户确认会在页面配置聊天模型。只读复核仍只看到 1 个启用的 `TYPESAFE_JEV` provider（`CHOICE/SCORE/NOUL`），启用 CHAT provider 数为 0；未读取 provider URL、模型密钥或加密字段，也未操作用户的 Edge/`8010` 页面。模型配置尚未保存可见，AI 对话会话及翻译实测仍待 CHAT 模型出现。
- 只读查询 `qa_user_20260929` 返回 0 行，未创建或变更用户/角色。QA 用户登录、普通用户权限与配额验收仍需用户在页面完成安全凭据交接，不能据此宣称全部整改验收通过。
- 本地服务复核：`8010` 登录页与 `8090` readiness 均 HTTP 200。健康状态不代表尚未完成的页面或联动验收已通过。

## 2026-09-29 17:26 Asia/Shanghai 独立源码复核汇总

- 监控、运营设置、字典、敏感词、语言包、导航树与响应式布局的独立复核未发现可确认的源码缺陷，未生成补丁。字典/敏感词/本地化定向后端测试 42 项通过；监控轮询 helper 14 项通过。主要剩余问题是页面证据与少数恢复/清理路径，不应把代码测试代替 UI 验收。
- 用户、角色、配额和反馈复核确认配额按角色分别取字节数/文件数最小值并可逐维回退；只读用户/角色详情使用快照读取；资料加载失败有重试。反馈分类选项和详情加载错误状态缺少重试，本地 QA 副本新增两个按钮，Node 24 typecheck 通过；主工作区和 `8010` 未改。后端定向测试 37 项通过。补丁为 `/tmp/lumira-next-feedback-permission-recovery-20260929.patch`，SHA-256 `1ae26087cc3566b98f940d0e240816e7e1abf92a8d4dd70a895933a4adce21e8`。
- 普通用户权限/反馈恢复仍因 QA 账号不存在而未做 UI 验收；不会代替用户填写密码或扩大角色权限。遗留角色/组织若要清理，正常页面路径是不可恢复的硬删除；尚无普通反馈记录删除入口；不做数据库/API 绕行或永久文件清理。

## 2026-09-29 17:27 Asia/Shanghai AI 补丁验证与模型状态复查

- 隔离 QA 副本中的 AI 改动已完成源码复核：provider 类型切换时失效旧发现请求、发现失败不恢复过期已选模型、类型/地址变更时不复用凭据；本地化 AI 翻译允许按语言权限发现并选择目录模型。Node 24 的发现测试 4 项、TypeScript no-emit 检查通过；Java 定向测试 11 项通过，其中真实发现使用 loopback fixture，聊天/翻译使用 mocks。主工作区、Edge、`8010` 均未改动；该补丁未进入实际预览。
- 17:27 的只读 PostgreSQL 复查仍见 1 个启用的 `TYPESAFE_JEV` provider、启用 CHAT 数为 0。没有调用推理。待用户保存一个已加载、启用的本地 OLLAMA/LM Studio CHAT 模型后，才能通过页面实际发起会话和翻译测试。

## 2026-09-29 17:30 Asia/Shanghai 主工作区构建复核

- 主工作区 `apps/web` 在 Node `v24.21.0` 下运行 `pnpm --dir apps/web typecheck`，退出码 0；`backend` 运行 `./mvnw -q -DskipTests compile`，退出码 0。
- 复查 `scripts/status.mjs` 显示 web PID 76301 与 backend PID 24256 仍在运行，8010 和 8090 health 均 HTTP 200。另一次只读 provider 查询仍为 1 个启用的 `TYPESAFE_JEV`（`CHOICE/SCORE/NOUL`）及 0 个启用 CHAT provider。当前主工作区编译通过，但隔离 AI/反馈补丁尚未合入运行版，真实会话仍不能启动。

## 2026-09-29 19:23 Asia/Shanghai 真实页面跟进验收

- **SMTP 本机测试：**补齐 SMTP/SMS 本机测试 POST 的既有 `platform:settings:write` 路由匹配，并修复投递成功后审计 `target_id` 为空违反非空约束。最终实际点击 SMTP 测试按钮得到接收端接受提示，审计页出现 `mail.localTest.ACCEPTED`；两条本机接收记录中第一条属于审计修复前的失败响应，第二条是闭环成功。原 SMTP 配置已通过页面保存、重载确认恢复，loopback sink 已停止。详见[SMTP 本机投递证据](evidence/smtp-local-delivery-acceptance-2026-09-29.md)。这不证明外部邮件、邮箱送达或运营商短信投递。
- **地区字段：**管理员个人资料页“地区”实际为文本框，非下拉；唯一合成标记通过保存、整页刷新后确认持久化，再清空、保存、刷新并确认恢复为空。资料字段配置页 Region 的说明字段另行做了保存/重载/清空闭环；该项是 schema 元数据验收，不能替代个人资料值验收。当前文本框无可执行的选项选择路径。
- **本地化：**`zh-CN/sensitiveImportCsvChooseFile` 当前恢复为内置 `选择 CSV 文件`，分类 `平台内置文案`/正常；敏感词导入页整页刷新后实际 consumer 仍显示默认值。操作审计当前可见 `localization.translation.restored`（目标 `dc227991-36c4-4123-8737-60c73e9cf312`）。先前“旧文案仍显示且无审计”的记录对应尚未点击确认的恢复对话框；具体旧页面缓存成因没有独立证据，不作推断。
- **短信：**页面显示服务关闭、Webhook 为空、访问密钥未配置；未发送。等待本地合成凭据/端点后再验，本轮不输入或猜测凭据。
- **AI 与字号：**用户将自行从模型接入页配置 CHAT 模型，当前未确认配置已保存，未读密钥；聊天和 AI 翻译实测仍阻断。项目浏览器脚本使用 API 登录并读取本地引导密码，不符合真实页面登录要求；当前可用 CUA tab 无 viewport override，因此认证工作台/用户列表在 1920、2560、3840 CSS 像素的验证仍未做，不以旧模拟结果替代。
- **服务约束：**`node scripts/status.mjs` 显示 8010 frontend PID 76301 和 8090 backend PID 49218 均 HTTP 200。8010 未刷新或重启；本机 SMTP sink 已停；用户要求保留的五个文件预览样本未变更。
- 细节及证据层边界见[本轮 UI 跟进证据](evidence/qa-session-followup-2026-09-29.md)。当前仍是部分 UI 验收，不能标记整批整改全部完成。

## 2026-09-29 21:12 Asia/Shanghai 本轮会话追加验收

- **组织筛选：**修复后的 8090 后端通过 `OrganizationRepositoryQueryTest`（1 passed、0 failures、0 errors、0 skipped）并在隔离 8020 管理员 UI 中按组织编码查询得到唯一目标行；Enter 保持结果，重置恢复 6 条。未选组织或保存用户。
- **角色/组织选择器表单：**避免嵌套 `<form>` 的 `component={false}` 修复只在隔离 8020 前端副本，Node 24 TypeScript 检查已通过；真实查询、Enter 和重置可用。既有记录称先前没有观察到 nested-form warning，但当前 CUA 未返回浏览器控制台，因此此处最新 console warning/error 数标记为**未验证**，不计零错误。
- **维护模式：**通过 UI 短时开启并用独立匿名登录页确认标题/说明展示；管理员页面会话可继续使用。随后清空标题/说明、关闭并保存；管理员整页重载后仍为空/关闭，匿名页重载后提示已消失。未以普通账号凭据测试拒绝登录。维护页还保留结束时间控件，用户要求的维护页简化尚未落实。
- **运行态：**当前 backend PID `62748` 与 web PID `76301` 均运行，8090/8010 HTTP 200。未导航、刷新或重启用户的 8010 页面；本轮使用 8020 隔离页面验收。
- **数据与边界：**本轮未清理任何角色、组织、反馈、敏感词演示批次/词条或 QA 语言包数据。按用户要求保留 `preview-source.doc`、`preview-source.docx`、`preview-test.txt`、`preview-test.md`、`preview-long.txt` 和两份原始文件；永久删除、清空回收站和删除后容量刷新用例暂缓。既有 `platform -> settings` 模块边界告警及未闭环的配置消费者仍在矩阵中跟踪；本轮邮件/字号等后续通过项不替代原始范围。
- **总体：仍未完成。**用户最新要求先不配置 CHAT 模型，因此 AI 聊天和 AI 翻译不启动、不测试。普通用户权限/配额/反馈与资料值、监控隐藏态及请求节奏、8010 新前端发布验收等仍有阻断项；Fast 实际运行档位也没有可确认的服务端字段，不能声称已开启。详情见[本轮会话式 UI 验收补充](evidence/qa-session-followup-2026-09-29.md#2026-09-29-2112-asia-shanghai-组织筛选修复与维护模式页面闭环)。

## 2026-09-29 21:32 Asia/Shanghai 最新进度更正

| 范围 | 最新证据 | 状态 |
|---|---|---|
| 模块边界/SMTP-SMS 测试入口 | 当前不是 `platform -> settings`；真实 smoke 故障为 `settings -> communication`。控制器迁至 mail/sms 包，路由与 `platform:settings:write` 检查保留。module smoke 通过，3 个安全/投递测试类共 10 项通过。 | 源码、测试及 8090 更新完成；未重发通知。 |
| 运行后端 | 8090 新 PID `66966` readiness HTTP 200，JAR SHA-256 `71fb2c6d144a78e477d6379ebc0af9ad331a095079b34a6299833a99a1d81f47`。8010 PID `76301` 未变，未导航/刷新用户页面。 | 后端运行版已含修复；新前端未发布到 8010。 |
| 品牌名称消费 | 8020 页面保存、刷新后仍保留；匿名登录页页脚实际展示更新名；恢复 `Lumira` 并刷新确认。 | 真实消费者闭环通过；4 条设置历史保留。 |
| 表单源码差异 | `users-page.tsx` 两副本相同；`FormDrawer component={false}` 两副本都有；额外 `AntForm component={false}` 仅在隔离 `user-editor.tsx`。 | 已纠正之前的文件定位；浏览器 console 正在核验。 |
| 维护页精简 | 移除 `endAt` 的补丁只在隔离 8020 副本，Node 24 typecheck 通过；主 8010 源码仍保留控件。 | 仅源码层就绪；待 8020 页面验收，不记为主站已修复。 |
| AI 对话/翻译 | 用户明确本轮先不配置 CHAT 模型。 | 暂缓，不尝试推理或读取密钥。 |
| 普通用户、隐藏态和数据 | 普通用户凭据交接、角色有效额度/权限、普通反馈、监控隐藏态、locale 恢复错误路径仍未闭环；保护的文件、敏感词演示词、角色/组织/反馈继续保留。 | 未完成或按用户要求暂缓。 |

完整证据见[2026-09-29 会话式 UI 验收补充](evidence/qa-session-followup-2026-09-29.md#2026-09-29-2132-asia-shanghai-模块边界修复运行更新与消费者复核)。

## 2026-09-29 21:41 Asia/Shanghai 控制台增量

- 8020 已通过真实 Edge DevTools 菜单读取既有 console：品牌页基线 5 条（1 info、3 warning、1 error）；进入用户列表→管理员编辑→组织归属后共 6 条（1 info、4 warning、1 error）。新增的是产品 `user-editor.tsx:133` `useForm is not connected to any Form element` warning；没有出现明确嵌套 `<form>` warning。用户/组织状态未修改。
- Ant Design Drawer `width` 弃用 warning 的当前调用栈来自第三方 Drawer/rc-component 包；两条 ProForm `initialValues` 消息是异步加载说明。`/api/auth/csrf` 504 的时间不可见，Network 条目已不可用，不能断定由 8090 重启造成，也未重放请求。
- 需要单独处理 `UserEditor` 的未连接 Form warning。品牌页 504 暂列未归因问题；整体验收仍未完成。

详见[8020 浏览器控制台实测](evidence/qa-session-followup-2026-09-29.md#2026-09-29-2141-asia-shanghai-8020-浏览器控制台实测)。

## 2026-09-29 21:44 Asia/Shanghai 隔离页面验收补充

- 维护页补丁已在 8020 隔离页面实际确认：只有标题、说明、允许角色与关闭状态的开关；无结束时间控件。当前标题/说明为空、角色 0/100、3 条候选且均未选；没有保存或切换。**8020 页面通过；8010 主页面仍未更新。**
- en-QA 恢复请求因 DevTools Network 未启用保留日志而不可检索；目前 `restore` filter 为 0/63，后端日志也没有对应服务日志。未重试、未绕过页面。故覆盖恢复根因仍未知。

详情见[隔离维护页与本地化恢复请求复核](evidence/qa-session-followup-2026-09-29.md#2026-09-29-2144-asia-shanghai-隔离维护页与本地化恢复请求复核)。
