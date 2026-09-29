# Lumira Next 软件著作权来源与核心功能排查

**检查日期：** 2026-09-26  
**对象：** 当前 `/Users/johntao/Documents/GitHub/lumira-next` 工作目录。  
**结论性质：** 源码、依赖和有限外部样本的技术排查；不是对全互联网代码的相似性证明，也不代替权属或许可的法律核验。

## 执行摘要

1. **未发现可以确认的第三方代码照搬。** 自写前端代码与本次取得的 Ant Design Pro 官方源码快照比较，没有检出连续 20 个相同词法 token 的跨文件片段。没有发现自写源码中的第三方版权头或“复制自”标记。这只排除本次样本中的明显逐段复制，不能证明全部源码由同一申请人独立完成。
2. **有两处已确认的核心功能缺陷。** 敏感词决策会因前 20 个 `LOG_ONLY` 命中而漏掉其后的 `BLOCK` 词；PostgreSQL 文件分块把每次 `InputStream.read()` 的返回量误当固定块，当前服务上 81,920 字节上传返回 HTTP 500。
3. **现有自动检查未全绿。** 后端 Maven `verify` 和前端类型检查、构建通过；`pnpm test`、数据库结构检查失败，且直接运行前端单元测试时发现六种语言的新增文案缺口。
4. **软著材料仍有权属证据缺口。** 当前目录没有独立 `.git` 历史；旧 Lumira 仓库里能找到部分代码的历史版本和完全相同的登录背景图，但这不能证明旧代码与图片的初始作者及其授权链。提交前应固定版本并留存申请人、贡献者及素材的权利凭据。

## 范围、方法与证明边界

| 类别 | 本次覆盖 |
| --- | --- |
| 自写源码 | `backend/src/main/java` 的 258 个 Java 文件、12,809 行；`apps/web/src` 的 151 个非生成 TS/TSX 文件、13,077 行；同时检查测试、脚本、配置、数据库迁移和发布路径。 |
| 排除项 | `node_modules`、Maven `target`、前端 `dist` 和 `.umi*` 生成代码、运行数据；它们不当作独立开发源码来判断。前端 `.umi*` 另有 32 个 TS 文件、2,624 行。 |
| 来源检查 | 搜索版权/来源标记和明显复制提示；以 20-token 连续词法片段对比官方 `ant-design/ant-design-pro` 快照 `24de7e3` 的 238 个 TS/TSX 文件；检查旧 Lumira Git 记录、图片 SHA-256，以及 pnpm/Maven 依赖树。 |
| 行为检查 | 阅读身份认证、敏感词、文件、AI、导航、队列等关键路径；运行现有构建/测试/本地服务检查，另用构造输入复现敏感词决策与文件上传。没有逐条执行所有浏览器脚本，也没有代表性生产数据的性能压测。 |

上述词法比较只覆盖**该官方仓库的本次快照**；其他版本、其他开源/私有项目、改写或翻译后的代码均不在这个“无匹配”结论内。使用 Ant Design、Umi、Spring Boot 等经包管理器引入的框架，本身不能据此推定为抄袭。我国《[计算机软件保护条例](https://xzfg.moj.gov.cn/front/law/detail?LawID=914)》要求受保护软件由开发者独立开发，并明确保护不延及思想、处理过程、操作方法或数学概念；技术相似度与权利归属仍须分别判断。

## 一、来源和许可排查

### 可确认的事实

| 编号 | 发现 | 证据与处理 |
| --- | --- | --- |
| C-01 | 未检出与官方 Ant Design Pro 当前快照的大段逐字相同代码。 | 比较了 151 个自写 TS/TSX 与 238 个官方 TS/TSX；无跨文件共享的连续 20-token 片段。框架组件 API、通用写法、短片段不在这个判断范围。官方项目的 [MIT 许可](https://github.com/ant-design/ant-design-pro/blob/master/LICENSE)允许按条件使用；即使后来发现有直接复制，也应核对许可保留要求。 |
| C-02 | 本项目含明确的旧 Lumira 代码迁移脉络，但申请人权属未由本次技术检查证明。 | 旧仓库在 `df702813`、`4b7e5c7a` 提交中有敏感词匹配器；当前 [`SensitiveWordMatcher.java`](../../backend/src/main/java/com/lumira/platform/sensitive/SensitiveWordMatcher.java)继承了标准化思路并重写了自动机结构。旧仓库根目录有 Apache-2.0 `LICENSE`，没有足以单独证明申请人就是原著作权人的记录。保留旧项目提交、参与者和权利转移/许可凭据。 |
| C-03 | 登录背景图能追溯到旧 Lumira 仓库，但原始创作或素材授权未证实。 | [`login-cloud-platform.jpg`](../../apps/web/public/login-cloud-platform.jpg)与旧仓库 `lumira-ui/src/assets/login-cloud-platform.jpg` 的 SHA-256 均为 `b546d521f12c6883b203e20fda2f7c801d1e6cb815260b5a784ba84356706ab0`；旧仓库 `ee79130b` 已追踪该文件。相同哈希只证明文件迁移，不能证明图片作者。补原始设计文件、购买记录或授权文件；若无法补齐，换用权属清楚的素材。 |
| C-04 | 当前工作目录没有独立 `.git`，无法从这里重建完整开发时间线。 | 旧仓库的 `git log --all -- lumira-next` 也未返回该目录的记录。应为此次软著版本固化源码树、文件哈希、创建/交付记录和申请人信息，之后持续保留提交记录。 |
| C-05 | 包管理器依赖与自写源码应分开披露。 | `pnpm licenses list --json --recursive` 得到 20 类许可标识；其中 `@stagewise/toolbar@0.6.2` 标为 AGPL-3.0-only，经 `@umijs/preset-umi → umi → @umijs/max` 进入**开发依赖**。应用源码没有直接引入，在本次新构建 JS 中也未检出 `@stagewise`；这**不构成已发布 AGPL 代码的证据**。两项包元数据为 `Unknown`，仍需按最终交付物核实许可证和说明文件。 |
| C-06 | 前端发布目录未见独立的第三方许可清单。 | [`web.Dockerfile`](../../deploy/web.Dockerfile#L9)将整个 `apps/web/dist` 复制进 Caddy；新构建 `dist` 未见 `LICENSE`/`NOTICE` 文件。建议按**实际打包依赖**形成第三方许可清单与应保留文本，再审查分发形式。不能仅凭缺少文件就断言侵权。 |

旧仓库许可证文本与申请人的著作权归属是两件事：若申请人拥有原作，可保留自身权利证明；若使用他人的原作，则应按照授权及适用许可处理。软著登记材料的要求、改作许可证明及源程序/文档鉴别材料，可核对国家版权局发布的《[计算机软件著作权登记办法](https://www.ncac.gov.cn/xxfb/flfg/bmgz/202410/t20241015_869486.html)》第七至十二条。

## 二、已确认的核心功能问题

### F-01 · 高优先级：敏感词拦截决策会被显示上限截断

- **代码路径：** [`SensitiveWordMatcher.find()`](../../backend/src/main/java/com/lumira/platform/sensitive/SensitiveWordMatcher.java#L37)在第 20 个不同命中时立即返回；[`SensitiveWordService.scan()`](../../backend/src/main/java/com/lumira/platform/sensitive/SensitiveWordService.java#L188)只根据已返回的命中计算 `blocked`；[`FeedbackService.create()`](../../backend/src/main/java/com/lumira/platform/feedback/FeedbackService.java#L29)使用这个布尔值决定是否拒绝反馈。
- **复现：** 以 20 个各不相同的 `LOG_ONLY` 词后接 1 个 `BLOCK` 词构造词典与文本，直接执行当前匹配器：`matches=20`，`blockDetected=false`。该结果由匹配器的提前返回路径决定；需要敏感词模块启用且存在这种词典配置，当前本地 14 个词的样本本身不满足条件。
- **影响：** 当规则和文本满足上述条件时，后面的拦截词未进入决策。显示给用户的命中条数上限不应约束后台策略判断。
- **建议/验收：** 扫描完整文本，并分别保存“是否出现任何 `BLOCK`”与最多 20 条展示记录；或提供独立的布尔决策扫描。增加超过上限、重叠词、尾部命中、大小写/空白标准化回归测试；从反馈创建 API 验证确实拒绝。

### F-02 · 高优先级：PostgreSQL 文件上传按读取次数分块，合法输入可能失败

- **代码路径：** [`PostgresBlobStore.writeChunks()`](../../backend/src/main/java/com/lumira/platform/files/PostgresBlobStore.java#L105)把每次 `InputStream.read(buffer)` 的返回量都写为一个块；[`stage()` / `publish()`](../../backend/src/main/java/com/lumira/platform/files/PostgresBlobStore.java#L36)却用 `ceil(文件总字节数 / 65536)` 验证块数。`InputStream.read()` 允许短读，返回长度并不保证填满传入缓冲区。
- **复现：** 当前运行中的本地服务（后端 8090、前端 8010，健康检查均 200），隔离测试账户上传 1,024 字节返回 HTTP 201；上传 81,920 字节返回 HTTP 500；把约 80 KiB 请求分段发送同样返回 HTTP 500。测试账户和文件记录已清理。独立短读流也能从代码逻辑推出块数不符。迁移旧文件调用同一个 `writeChunks()`，因而也存在相同分块风险。
- **影响：** 大文件上传与旧文件迁移可靠性受输入流分段方式影响。现有 [`files-smoke.py`](../../scripts/files-smoke.py#L29)仅覆盖很小的正文和空文件；没有找到 PostgreSQL 分块存储的专用回归测试。
- **建议/验收：** 累积读取数据，到 64 KiB 或 EOF 才写一块，并同步校验大小和 SHA-256。增加短读流、64 KiB±1、80 KiB、多块大文件及旧文件迁移测试；实测正常与分段 HTTP 上传都返回 201，下载内容与哈希一致，错误路径无残留块。

### F-03 · 中优先级：迁移后自动检查脚本未同步

- [`module-boundaries-smoke.py`](../../scripts/module-boundaries-smoke.py#L112)只从 V1 提取表，而 `module-ownership.json` 已包括 V5/V6 新表，`pnpm test` 先在此处失败。脚本第 35–91 行还重复执行一次模块依赖图校验。
- [`database-schema-smoke.py`](../../scripts/database-schema-smoke.py#L24)只接受 V1–V3；执行 `pnpm db:schema-smoke` 时，实际 V1–V6 列表立即触发断言，尚未检查数据库状态。
- **建议/验收：** 以所有有效迁移及实际数据库对象为准校验归属，保留对意外表/对象的拒绝；移除重复图检查。两个命令都能执行到完成，并对故意遗漏归属的测试样例失败。

### F-04 · 中优先级：六种语言缺少新增文案，单元测试失败

- [`messages.ts`](../../apps/web/src/platform/i18n/messages.ts) 中 `zh-TW`、`ja`、`ko`、`fr`、`es`、`de` 各比 `en`/`zh-CN` 少 79 个新增 AI 相关键；[`i18n.test.mjs`](../../tests/i18n.test.mjs#L9)要求键和占位符一致。直接运行前端测试为 20 通过、1 失败。界面会退回英文文案，但多语言体验和 CI 约束均不完整。
- **建议/验收：** 补全实际语言译文与占位符，避免以英文复制充数；运行全部 21 项前端单元测试及 `pnpm test`。

## 三、按收益排序的优化机会

| 编号 | 优先级 | 现状和证据 | 建议与测量方式 |
| --- | --- | --- | --- |
| O-01 | 高，修复 F-01/F-02 后 | [`SensitiveWordService.importWords()`](../../backend/src/main/java/com/lumira/platform/sensitive/SensitiveWordService.java#L131)一次最多 5,000 词，却对每词执行一次插入；V6 [`platform_bump_sensitive_word_revision()`](../../backend/src/main/resources/db/migration/V6__database_backed_file_and_ai_state.sql#L47)又在每行更新同一版本记录。这是可推断的批量导入写入/锁争用点，本地只有 14 词，**未做 5,000 词压测**。 | 改批量插入并将版本更新设计为每语句或每事务一次，同时维持跨实例重载语义；对 1k/5k 词测总耗时、DB 往返和锁等待。 [`LocalizationService.importEntries()`](../../backend/src/main/java/com/lumira/platform/localization/LocalizationService.java#L122)也有逐条插入，可一并测量。 |
| O-02 | 中 | [`ModelConnectionService`](../../backend/src/main/java/com/lumira/platform/ai/ModelConnectionService.java#L104)先用 `BodyHandlers.ofString()` 收完整外部模型响应，再检查字符串是否大于 2,000,000 字符。上限不能约束接收过程中的内存峰值。 | 以有界流读取/响应体订阅器按**字节**限制，再解析 JSON；用超限的模拟模型端点验证快速中断和统一错误码。 |
| O-03 | 中 | 新构建有 39 个 JS 文件、合计 3.49 MB 原始体积；首个 `umi` 文件 1.47 MB、gzip 约 460 KB。另有 39 个 source map，合计约 12.18 MB，随 `dist` 一起进入发布镜像。[`providers.tsx`](../../apps/web/src/platform/providers.tsx#L9)同步引入八套 Ant Design locale，`messages.ts` 自身约 322 KB。 | 先用目标浏览器/网络条件测首屏传输和解析，再考虑按语言延迟加载字典和 locale。生产构建禁用或在镜像中排除公开 source map；如调试需要，保留在私有错误分析系统。 |
| O-04 | 视数据量而定 | [`UserRepository`](../../backend/src/main/java/com/lumira/platform/users/UserRepository.java#L12)及敏感词等列表使用 `strpos(lower(...), ...)` 和 `OFFSET`。普通 B-tree 索引通常不能加速任意子串搜索，大页码还需跳过前面的行；本地用户 4 条、敏感词 14 条，**尚无实际性能故障证据**。 | 用代表性数据执行 `EXPLAIN (ANALYZE, BUFFERS)`、P95 测量；确有瓶颈再评估 `pg_trgm`、专门索引或 keyset 游标。与既有 page API 的兼容性一并设计。 |
| O-05 | 持续维护 | 自写后端中有邮件/短信查询、国际化/敏感词列表等平行实现；逐段相似性多数是同一项目中的结构复用。已有密码 Argon2id、敏感词 Aho–Corasick、队列 `SKIP LOCKED`、导航锁等合理选择；未发现需要盲目替换为“更新算法”的证据。 | 只在修复或新增功能时抽取确实共享的不变量，保留各模块领域规则；用吞吐、延迟和维护成本检验收益。对手写 WebAuthn 验证器持续做规范与安全回归，避免仅因依赖存在而草率替换。 |

软著说明书可据实描述的系统实现包括：[`ModuleRegistry`](../../backend/src/main/java/com/lumira/platform/modules/ModuleRegistry.java)对模块依赖排序和环路校验；[`DurableDeliveryWorker`](../../backend/src/main/java/com/lumira/platform/communication/DurableDeliveryWorker.java)统一邮件/短信的有界投递、租约续期与结果处理；[`IdentityConfiguration`](../../backend/src/main/java/com/lumira/platform/identity/IdentityConfiguration.java)对新旧密码摘要的版本化迁移。这些是本项目代码中能够核对的设计与实现，**不等于基础算法由本项目发明**。文件分块和敏感词拦截的实际行为应在修复 F-01/F-02 后按新版源码表述。

## 四、已运行的验证

| 命令/检查 | 结果 | 解释 |
| --- | --- | --- |
| `./backend/mvnw -q -f backend/pom.xml verify` | 通过；Surefire XML 共 271 项，0 失败、0 错误、91 跳过。 | 已有后端测试没有覆盖 F-01 的决策截断或 F-02 的 PostgreSQL 短读。91 项跳过不应计为覆盖。 |
| `pnpm typecheck`、`pnpm build` | 通过。 | 编译通过不代表核心行为正确。 |
| `pnpm test` | 失败于模块归属检查（F-03），未进入前端单元测试。 | 在这一关修复前无法用总命令证明全部通过。 |
| `node --test tests/*.test.mjs` | 20 通过、1 失败（F-04）。 | 当前 shell 为 Node v22.23.1，而 `package.json` 要求 Node 24；报错是明确的键集合不一致，仍应在指定 Node 版本复核。 |
| `pnpm db:schema-smoke` | 失败于只接受 V1–V3 的静态断言（F-03）。 | 未执行到数据库结构的实际比较。 |
| `node scripts/status.mjs` | 8090 后端与 8010 前端均运行，HTTP 健康检查均 200。 | 健康检查不能替代业务路径验证；文件上传的 live 复现已经显示区别。 |

## 五、软著提交前的具体整理顺序

1. 先修复 F-01、F-02 和检查脚本/多语言失败项，再在要求的 Node 24 和当前后端运行环境下跑完对应回归。此前不宜把“核心功能全面稳定”写进说明书。
2. 核对申请主体是否拥有旧 Lumira 代码的权利，整理开发合同/职务成果/转让记录、旧仓库提交和此次迁移记录；补登录背景图的创作或授权凭证。没有证据的素材可替换后再冻结版本。
3. 固定一个可复现的源码版本、依赖锁文件与文件哈希。交存材料从真实自写源码和对应说明文档生成；将依赖包、构建生成代码、`dist`、运行数据、第三方图片与申请人代码区分。具体页数、例外交存和改作材料按国家版权局《[计算机软件著作权登记办法](https://www.ncac.gov.cn/xxfb/flfg/bmgz/202410/t20241015_869486.html)》执行。
4. 对**实际发布物**生成并随版本留存第三方组件/许可证清单，核对 Umi、Ant Design Pro 等许可条件和开发依赖是否打包。官方 [Umi](https://github.com/umijs/umi/blob/master/LICENSE) 与 [Ant Design Pro](https://github.com/ant-design/ant-design-pro/blob/master/LICENSE) 源码库均采用 MIT 许可；最终义务还取决于实际复制/分发内容。

**本次只新增这份排查报告；没有修改业务源码、数据库结构或软著正式资料。**
