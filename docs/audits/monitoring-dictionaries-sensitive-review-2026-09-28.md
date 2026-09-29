# 监控、业务字典与敏感词策略审查

日期：2026-09-28  
状态：2026-09-28 的统一集成窗口已完成：Node 24 前端 typecheck/build 通过；Maven verify 326 项中 231 项通过、95 项跳过、0 失败/错误；V1–V15 均成功，V15 已应用；前后端 health/readiness 与实际 `/umi.js` 均验证正常。数据库 schema smoke 在把四个由 V5 明确定义且当前存在的 AI 页面补入严格期望集后通过。源码/UI 分层结论见下文：监控故障退避修复已进入当前构建，但故障恢复与真实后台隐藏 UI 回归尚待解锁后复测；V15 分类消费和 F-01 阈值清理 UI 也待完成。此前的监控、反馈分类 UI 证据保留为历史交互证据，不替代当前源码后的回归验收。

## 结论边界

服务健康检查返回 HTTP 200 不代表页面行为全部通过。此前 `/umi.js` 曾返回 HTML 并导致白屏；这是已恢复前的历史状态。最后一次受控启动后，Flyway V1–V15 全部 success=true，日志确认从 schema v14 应用一个迁移到 v15。`node scripts/status.mjs` 当前读到 backend PID `25914`、web PID `25915`，两端 health 为 HTTP 200；readiness 为 `UP`；独立 GET `/umi.js` 返回 HTTP 200、`text/javascript; charset=utf-8`、17,586,597 bytes。它证明当前静态资源服务正常，不证明锁屏期间无法执行的浏览器 UI 行为已经复验。旧 UI 矩阵中后台隐藏未形成 `visibilityState=hidden` 样本；成功响应后轮询约 6 秒的缺陷已做源码修复和集成，但需在解锁后重新实测。

## 统一构建与迁移状态

- 最终统一窗口使用 Node 24 执行 `pnpm --dir apps/web typecheck`、locale catalog 生成（948 keys / 8 locales）和 `pnpm --dir apps/web build`，均退出 0。
- `./mvnw -B -ntp -f backend/pom.xml verify` 最终退出 0：326 tests，231 passed，0 failures，0 errors，95 skipped。首次完整尝试曾有 2 failures、5 errors；根因是 `Set.of(...).contains(null)` 的 NPE 和一个测试对同一成功更新调用同时断言 `never()`。修复了生产空值校验与测试断言后重跑全量 verify，未跳过或放宽失败用例。另有 focused results：role quota 9/9、organization membership 2/2、dictionary services 15/15、sensitive category 6/6。
- Flyway V1–V15 只读状态均为 success=true；V12 角色文件额度、V13 反馈分类、V14 语言注册表、V15 敏感词分类字典均应用。统一启动前为 v14，启动后为 v15。
- `python3 scripts/database-schema-smoke.py` 首次失败在内置页面集合：V5 SQL、web manifest 和页面注册均定义的四个 AI 页面未包含在脚本期望集合。最小修正只添加 `ai-capabilities`、`ai-console`、`ai-judgment`、`ai-models`，并把内置导航条数从 34 更新为 38，保留对集合相等的严格校验；之后 smoke 通过。
- 当前 `scripts/status.mjs` 显示 backend PID `25914`、web PID `25915`，8090/8010 health 均为 HTTP 200；readiness 为 `UP`。`/umi.js` 返回真实 JavaScript（HTTP 200、`text/javascript; charset=utf-8`、17,586,597 bytes）。
- 第一次构建/迁移尝试的失败和更早 V14 启动错误均为已修复历史；V15 启动迁移及其后的服务状态以以上最后窗口为准。
- 本地旧 V11 用户额度 override 的只读聚合数为 0，超过系统默认额度的账户数为 0；本地 V12 无现存个人额度需要回填。该结论不替代其它部署升级前的旧 override 兼容步骤。

## 运行与迁移基线

- 集成窗口前的 PID 与失败启动记录均为历史。当前服务进程为 backend `25914`、web `25915`，两端 health 均 HTTP 200。
- Flyway 只读查询确认 V1–V15 全部成功；V12 owner `directory`、V13 `feedback`、V14 `localization`、V15 `sensitive-words`。
- 当前服务 bundle 已返回 JavaScript；浏览器 UI 仍受 macOS 锁屏阻塞，因此本轮不把旧 UI 证据写成 V15 后的最新视觉验收。
- 历史白屏期间曾对前端执行一次获准的 `touch`；当时 `/umi.js` 返回 `text/html`（1112 bytes）。这是恢复服务前的历史观察，不能代表当前已恢复页面。

## 监控刷新

代码位于 `apps/web/src/features/monitoring/monitoring-page.tsx`。页面提供实时约 3 秒轮询、5/10/30 秒、1/5/15 分钟、1 小时及手动刷新。请求使用 TanStack Query 和 `AbortSignal`；后台隐藏时暂停轮询，请求进行中不叠加新请求，失败后按间隔退避，自动重试关闭。当前监控 API 是只读快照请求，没有服务端推送，因此“实时”明确表示约 3 秒轮询。

刷新频率、更新时间等 7 个新消息键已加入八种静态 locale。最终静态目录有 947 个键，八种 locale 的 key 集合与占位符通过检查；本任务的 15 个监控/敏感词新建或更新键在八种 locale 中均存在。

**最终 bundle UI 矩阵：**见 [monitoring-final-acceptance-2026-09-28.json](evidence/monitoring-final-acceptance-2026-09-28.json)。实时、5 秒、10 秒相邻 GET 间隔分别为 3.029、5.025、10.024 秒；60 秒档完整等待 60.438 秒后收到 HTTP 200。30 秒、5 分钟、15 分钟和 1 小时均在页面选中，各观察 6.5 秒未见自动请求（短于完整档位）。手动模式观察 8 秒无自动 GET，点击“刷新页面”后 36 毫秒发出 HTTP 200。离开到通知中心 10 秒无 summary GET；返回后选项重置为默认 5 秒，随后收到 200。

**失败与恢复：**此前浏览器只拦截一次页面发起的只读 summary GET 并返回 503；手动模式 4.5 秒无自动重试。切回实时后 6.469 秒收到 200，但后续相邻请求仍为 6.044、6.027 秒。源码检查确认 TanStack Query 5.102.8 的 `errorUpdateCount` 成功后不清零，而 `fetchFailureCount` 会清零；原实现用前者计算倍增，导致一次错误后的慢周期持续。现在 `getMonitoringRefetchInterval` 使用 `fetchFailureCount`，针对性 Node 测试 3/3 通过，并已进入 Node24 build 和最终集成启动。锁屏期间无法重测故障恢复后的新周期；该 UI 回归仍待解锁。

**后台暂停未验证：**Playwright 第二页、Finder 前台切换和辅助 Edge 标签均没有使目标页报告 `document.visibilityState=hidden`，期间仍观察到 GET。见 [monitoring-background-e2e-2026-09-28.json](evidence/monitoring-background-e2e-2026-09-28.json)；这些不是有效的隐藏页样本，明确不记为通过。真实标签隐藏/恢复检查需等待协调方指定的前台槽位。

早期预集成的 3 秒观测保留在 [monitoring-refresh-e2e-2026-09-28-partial.json](evidence/monitoring-refresh-e2e-2026-09-28-partial.json)，已由最终 bundle 证据补足，不能单独作为验收结论。工具栏截图仅显示刷新控件，未包含用户或敏感数据。

## 业务字典候选与反馈分类

覆盖审查结论：反馈优先级已由字典驱动并被表单、列表和后端消费；反馈状态及终态规则属于工作流状态机，应保持为受保护配置；个人资料自定义字段字典已接入，但本轮不改其存储与配置。候选中新增“反馈分类”`work_order_feedback_category`。角色/权限、路由菜单类型、安全策略、协议和消息生命周期状态等固定枚举不应作为一般业务字典开放编辑。

`backend/src/main/resources/db/migration/V13__business_dictionary_expansion.sql` 为 `platform_feedback` 增加可空类别代码、格式约束和最近反馈索引，并种入五个稳定代码：`QUESTION`、`BUG`、`FEATURE`、`ACCOUNT`、`OTHER`。旧记录保持 `NULL`，不做猜测回填。代码不可改写；标签、顺序和启停可维护。反馈创建、筛选、列表/详情标签及服务端校验已接入字典读取。禁用项不进入新建选项；旧草稿引用已禁用或缺失项时会清空字段并触发原有必填校验，选项未检查完前不能提交。

**源码/静态检查：**V13 的 migration/table/object ownership 覆盖检查已通过；`database-schema-smoke.py` 包含新列、种子、索引和约束断言。另有独立模块边界 smoke 报告 `platform -> settings` undeclared dependency（`AiProviderService` 引用 `settings.SecretStore`）；脚本 fail-fast，不能断言这是唯一潜在余项，该边界问题与 V13 无关。

**UI / 持久化验收：**通过可见菜单完成唯一测试类别 `QA_TEMP_CATEGORY_20260928` 的创建、改标签、排序 15、刷新后持久化、禁用与刷新后持久化。启用时创建表单显示测试类；禁用后反馈过滤与创建选项均不再出现该项。以该类别保存合成草稿，刷新后恢复并确认类别/标题还原，再通过 UI 删除草稿。未提交反馈记录，因为当前反馈列表没有 UI 删除入口；因此不声称已验证保存的反馈行 linkage。最后经 UI 只删除本轮唯一测试类并刷新，字典表恢复到五个 V13 seed 项。具体逐步结果和限制见 [dictionary-feedback-ui-acceptance-2026-09-28.md](evidence/dictionary-feedback-ui-acceptance-2026-09-28.md)。没有安全的本地目标标签截图导出路径，本轮未保存字典截图。

完整复用字段与保护边界源盘点见 [dictionary-coverage-inventory-2026-09-28.md](evidence/dictionary-coverage-inventory-2026-09-28.md)。覆盖反馈 `status`、`priority`、`default`、`category` 四种字典，以及用户资料自定义 `SELECT` 的 `dictionaryCode`。敏感词 `category` 是业务组织/显示字段，与继续固定的 `severity` 和 `action` 安全合同分离。V15 已应用并经 migration test、schema smoke 和全量 verify 覆盖：它为 `sensitive_word_category` 种入 `platform_sensitive_word` 中每个现存类别值（包括软删除行上的值）及 `DEFAULT`、`IMPORTED`，不改写词条行；敏感词创建、编辑、导入及分类过滤通过仅返回启用项的选项端点消费该字典，旧的禁用/缺失值继续可原样显示，matcher 不读取类别。敏感词 usage adapter 只阻止仍被活动词条引用的类别变更/停用/删除，软删除词条不再阻塞清理。分类消费/清理 UI 仍待真实复验。

`DictionaryItemService` 和 `DictionaryService` 的条目级与主记录级保护源码已进入本轮全量 verify；status 的代码/启用/`TERMINAL` 语义、priority/default 依赖、已使用类别，以及五个消费者主字典的启停/删除/平面结构均有 guard。`DictionaryItemUsagePort` 由反馈和敏感词适配器实现，反馈扩展 priority 另有默认颜色回退。数据库 schema smoke 已通过。独立模块边界 smoke 的已知 `platform -> settings` undeclared dependency（`AiProviderService` 引用 `settings.SecretStore`）仍是单独的架构问题，本轮没有重构或把它并入 V15 验收。

已知源码限制：字典管理页锁定受保护主记录的结构选择；若已有受保护字典是 `TREE`，管理页没有修复该结构的操作。本地 V14 只读 DB 检查显示四个反馈 policy 字典均为 `FLAT,true`；V15 后尚需确认 sensitive dictionary UI 选择路径。敏感类别字典输入框以 128 个 UTF-16 code units 作为浏览器长度上限，后端仍是 64 Unicode code points 的权威校验。监控修复的 UI 复测、敏感分类验收/合成词清理仍待前台槽位。

## 敏感词策略与 F-01

`SensitiveWordMatcher.scan` 保留最多 20 条展示结果，同时继续扫描完整文本，独立计算阻断状态和不同规则命中数。`SensitiveWordService.CheckResult` 新增 `totalMatchCount` 与 `blockingMatchShown`，旧 `matches` 列表仍有上限；前端分别显示准确总数、展示上限，并在阻断命中落在展示区外时给出说明。日常表单简化为词语、分类，级别、动作、状态收在高级区；新建和批量导入默认 `LOG_ONLY`。

回归单测已补充“20 条仅记录后，尾部阻断规则不能因展示上限而漏判”的情况，并在此前完整 Maven verify 中通过。`ScanResult` 全文扫描得到总数与阻断状态，`SensitiveWordService.CheckResult` 的 `totalMatchCount`/`blockingMatchShown` 与前端字段匹配。JavaDoc 已更正为实际采用的 NFKC + lowercase conversion。历史真实 UI 操作曾以 20 个 `LOG_ONLY` 加尾部 BLOCK 测得总数 21、展示 20 项并判定阻断；反馈创建 UI 也曾拒绝同一组标记且没有创建记录。

**当前验收处置更新：**上述点击与截图保留为历史 UI 证据，但不作为本次最终阈值关闭证明。V15 已应用。V14 时点只读数据库快照显示 F01 分类有 20 条启用行，包括尾部 BLOCK 和 19 条 `LOG_ONLY`（`LOG_10` 已无活动行）；V15 不改写词条行。解锁后需经 UI 新建两个唯一中性 `LOG_ONLY` 标记，使输入至少包含 21 个启用 `LOG_ONLY` 加尾部 BLOCK，再用页面确认至少 22 总命中、20 项展示上限、BLOCK 结果和隐藏命中提示。之后精确清理新增与剩余活动标记，保留 8 条旧行和 12 条演示词，并仅在无活动引用时删除 `QA_F01_20260928` 字典项。该 UI 回归和清理当前待验。

### 演示与临时数据

- 可复用 JSON：[sensitive-word-demo-dataset-2026-09-28.json](evidence/sensitive-word-demo-dataset-2026-09-28.json)。共 12 条中文中性短语，隐私与保密、营销承诺、交易风险三组各 4 条；均为 `MEDIUM`、启用、`LOG_ONLY`。数据说明明确其为演示用途，不是官方、完整或合规词库。
- 12 条演示词已通过现有管理 UI 的批量粘贴导入；本任务要求保留。
- 初始 UI 明细由 [sensitive-ui-preflight-state-2026-09-28.md](evidence/sensitive-ui-preflight-state-2026-09-28.md) 留档：8 条既有 `qa-sensitive-*`、20 条 F-01 LOG_ONLY、1 条 tail BLOCK、12 条演示词和 1 条临时短语，共 42 条。该证据明确初始既有记录为 8，不是 29；不能为了凑出 41 删除或改动任何未标记行。
- 12 条演示词必须保留；8 条既有行不触碰。临时短语 `付款前核验收款账户` 已通过 UI 软删除。早先的一组 F-01 fixture 也已经通过 UI 软删除；后续重导入的标记中，精确活动数以最新的只读数据库快照为准。
- 较早 UI 列表曾观察到 41 条活动记录。2026-09-28 的只读 V14 数据库快照（V15 应用前）确认当时总数为 40：8 条既有、12 条演示、F01 分类 20 条（19 条 LOG_ONLY + 1 条 tail BLOCK）。V15 未重写词条行，但 V15 后尚无新的 UI/数据库计数复核；`LOG_10` 在该快照中无活动行且保留两条软删除副本。详见 [敏感词精确数据计数与引用证据](evidence/sensitive-ui-preflight-state-2026-09-28.md)。不能把较早的 41 条 UI 截图当作当前实测，也不能为凑数删除未标记行。
- Mac 解锁且 V15 应用后，需经 UI 再创建两个唯一中性 `LOG_ONLY` 词条，使回归输入为至少 21 个启用 LOG_ONLY 加尾部 BLOCK；记录两个词条的精确文本、ID 与 `QA_F01_20260928` 分类引用，页面检查后仅按名称软删除新增词条及剩余活动 F-01/tail 行。所有 21 个原名和两个新名活动数应为 0；仅当 active reference guard 确认无在用项时，通过 UI 删除 `QA_F01_20260928`。保留旧行、演示词和它们共享的 `演示-交易风险`。清理后的总数按实际确认，预计为 20（8+12）；若不同则停止并报告差异。

UI 交互结果、当前状态及截图见 [sensitive-ui-preflight-state-2026-09-28.md](evidence/sensitive-ui-preflight-state-2026-09-28.md)、[sensitive-f01-hidden-block-2026-09-28.jpg](evidence/sensitive-f01-hidden-block-2026-09-28.jpg) 和 [sensitive-f01-visible-block-2026-09-28.jpg](evidence/sensitive-f01-visible-block-2026-09-28.jpg)。清理后的持久化/计数验证仍待前台槽位。

## 证据目录

- `docs/audits/evidence/sensitive-word-demo-dataset-2026-09-28.json`：可复用的演示数据和适用范围说明。
- `docs/audits/evidence/sensitive-ui-preflight-state-2026-09-28.md`：修复版 F-01 隐藏/可见命中与实际反馈提交阻断的 UI 证据、原始明细、当前标记及清理预期。
- `docs/audits/evidence/monitoring-final-acceptance-2026-09-28.json` 与 `monitoring-background-e2e-2026-09-28.json`：最终刷新档位/手动/失败恢复/离路由矩阵，以及未能形成 hidden 样本的记录。
- `docs/audits/evidence/dictionary-feedback-ui-acceptance-2026-09-28.md` 与 `dictionary-coverage-inventory-2026-09-28.md`：反馈分类 UI CRUD/草稿闭环及全项目字典与固定值合同源盘点。没有可安全导出的字典目标标签截图文件；未将临时图像观察冒充保存证据。
