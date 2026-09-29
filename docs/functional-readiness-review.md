# 当前功能完成度检查

## 2026-09-15 数据库初始化基线（最新）

已将原 V1–V48 收敛为一份 `backend/src/main/resources/db/migration/V1__platform_initialization.sql`。新环境直接导入这份 PostgreSQL 18.6 基线，生产 Flyway 目录没有其他增量 SQL；旧脚本只作为测试夹具保存。基线包含最终表结构、约束、索引、视图、触发器和稳定的模块/字典/导航种子，不包含管理员密码或渠道密钥。

本地数据库已先备份再重建，Flyway 当前为 `1 platform initialization`。`pnpm db:schema-smoke` 验证单文件、37 张平台表、关键不变量和种子；受保护的 `pnpm db:reinitialize` 会在重建前生成备份。数据库在线升级不能覆盖已执行的基线，需按 [数据库初始化说明](database-initialization.md) 走受评审迁移或备份重建。

检查日期：2026-09-15。对象：lumira-next、本地 8010 前端和 8090 后端。结论：平台公共管理功能已完成本地实现与回归，赛事官网和业务域仍未迁移；不能据此替换旧系统或宣称生产高并发、高可用达标。

## 2026-09-15 组织数据范围复验（最新）

- 组织树读取现在与用户列表共用数据库可见性边界：SELF/ORGANIZATION、SUBTREE、ALL 分别限制组织列表、父节点筛选、详情和祖先链；范围外详情按 404 处理，不泄漏组织是否存在。
- `scripts/data-scope-smoke.py` 已覆盖四种范围的用户与组织结果、即时改权、隐藏详情和无关权限不扩大范围；`scripts/organizations-smoke.py` 保持管理员层级、分页、循环、版本冲突和删除保护回归通过。
- 组织结构/归属写入仍要求全局组织写权限；其他公共资源要在建立 owner/organization 归属字段后逐项接入同一策略，不能把本次组织边界扩大解释为所有资源已完成。

## 2026-09-15 安全策略闭环复验（最新）

- 新增数据库化 Passkey/WebAuthn 公共适配：`platform_passkey_challenge` 使用会话绑定、过期时间和原子消费防重放，`platform_passkey_credential` 保存 COSE 公钥、计数器和设备元数据；认证接口校验 RP ID、Origin、UP/UV 标志、ECDSA/RSA 签名和单调计数器。默认关闭，开启前必须由管理员通过版本化 `/api/platform/settings/passkey` 配置策略；`scripts/passkey-smoke.py` 已验证 CSRF、功能闸门和安全字段投影。真实硬件认证、生产 HTTPS/RP 域名和多实例容量仍需单独验收。

- 新增强类型 `SecuritySettings` 与 `/api/platform/settings/security`，统一维护密码最小长度/字符规则、登录与找回密码账号/来源限流、验证码有效期/冷却/每分钟和每日配额/失败上限。策略保存使用 `SettingStore` 的乐观版本、审计、历史详情和回滚；登录、找回、邮箱/手机验证码限流与挑战存储读取同一份策略，热路径采用 2 秒有界缓存。
- 新增 `/dashboard/security`，复用共享 `SettingsSection`、`SettingsHistory`、Ant Design 6 表单和八语言目录。专项浏览器回归覆盖所有字段读写、历史版本、回滚、刷新持久化、pageerror 和横向溢出；全量 `test:browser` 已纳入该脚本。
- 本次后端 `clean verify` 共 256 项，失败 0、错误 0（88 项外部依赖测试按环境跳过）；前端 Node 24 类型检查、Next.js 生产构建、17 项 Node 测试、schema smoke、模块包 HTTP smoke 和全量浏览器回归均通过。用户管理 CSV 导出已接入读取权限和数据范围过滤，并由 API/浏览器下载回归覆盖。
- 会话 Cookie 生命周期、CAPTCHA/MFA 供应商、外部短信/邮件送达以及生产多实例限流共享仍需实际运行时和生产环境单独验收，未在页面中添加不能生效的伪配置。
- 模块管理新增声明式包登记闭环：`platform_module_package` 只保存规范化 manifest、制品引用和摘要；重复登记幂等，激活/拒绝受 revision 与图锁保护，激活要求静态代码版本和依赖图匹配。模块包浏览器回归覆盖登记、运行时未部署提示和拒绝；服务端不下载或执行上传制品。
- 邮件/短信队列新增终态保留清理：默认保留 30 天、每批最多 1000 行、使用 `SKIP LOCKED` 并发安全，只删除 `ACCEPTED/FAILED/UNCERTAIN/EXPIRED`，不会触碰租约中或发送中的任务；审计记录单独保留。

## 2026-09-14 历史复验（上方 2026-09-15 复验优先）

反馈公共能力已完成平台适配并纳入全量验收：V34–V38 存储、共享文件引用、字典策略、模块开关、独立权限、本人/管理员范围、富文本清理、版本化处理和事务审计均已通过；V43 已补齐反馈创建表单的账号草稿保存、恢复和消费修订清理，V43/V44 补齐导航地址唯一性和受控组件键注册，V45 补齐敏感词策略的词条管理、匹配检查、批量导入与反馈阻断，V46 补齐运行时本地化的版本化文案、导入、发布和公开 bundle，V47 补齐短信配置、加密短信队列和账号手机验证，V48 补齐短信队列运维查询；登录限流/超长密码拒绝审计和审计保留清理也已接入。共享 `PlatformDrawer` 的 Escape 关闭和焦点恢复已修复；账户键盘、双层/树形导航、自动隐藏动效、窄屏抽屉、八语言、安全动态 PAGE、敏感词、本地化、短信/手机号入口和短信队列查询回归通过。启用本地 PostgreSQL 夹具的后端 `clean verify` 242 项（失败/错误/跳过均为 0）、前端 Node 24 类型检查/生产构建、17 项仓库测试和全量浏览器脚本均通过，浏览器脚本覆盖草稿、模块、导航、安全、账号、设置、文件、邮件、短信、反馈、敏感词和本地化路径。

本轮仍保持边界：赛事、报名、支付、评审、证书、项目/团队等业务流程不迁移。生产部署、备份恢复、容量压测、故障切换、外部邮箱投递以及动态模块制品扫描/发布流水线仍需独立验收。

后续修复：下文第 1 项移动端顶部溢出已修复。手机端使用紧凑账户菜单，与双层侧栏共用 AccountMenu；桌面分区按钮保留。两种布局、八语言 320px 首页无横向溢出，390px 资料/会话/密码窗口及导航打开关闭通过，桌面账户位置通过。移动导航按钮的定位优先级也已修正，位于视口底部。新脚本 scripts/shell-responsive-browser-smoke.mjs 加入标准浏览器回归入口；这不代表全部移动页面或键盘交互已验收。首次脚本立即按 Escape 关闭资料抽屉未成功，后续使用明确的关闭按钮验证；键盘焦点和 Escape 行为仍需专项复核。

## 本次实际验证

后续键盘修复：账户抽屉快速关闭时的焦点丢失已在共享 PlatformDrawer 修复，账户菜单使用自动聚焦和稳定入口返回。桌面/390px 两种导航布局的键盘打开、菜单方向键、Tab、Escape、返回入口及嵌套邮箱窗口已通过专门回归；其他页面及自动隐藏动效仍需继续验收。

- 登录页面 HTTP 200，后端健康 UP。
- TypeScript 检查、17 项仓库测试和生产构建已在 V47 复验通过（本段其余数量为历史记录）。
- 登录 HTTP 回归通过：匿名拒绝、CSRF、错误密码、登录退出、撤权即时生效、105 条用户分页与注销会话重放拒绝。
- 会话和数据范围 HTTP 回归通过：本人/其他账号隔离、单个/其他/当前会话撤销，SELF/ORGANIZATION/SUBTREE/ALL 及即时授权变更。
- 浏览器访问全部 15 个已注册后台页面，HTTP 200；1213×886 观察期间无 pageerror、API 错误响应或页面横向溢出。该检查是入口加载检查，不等于逐个按钮和持久化流程验收。
- 双层导航不存在 rail-brand 装饰节点，账户操作未重复显示在顶部。未进行自动隐藏动画的逐帧回归。
- 历史 Surefire 报告中的 143 项不再代表当前构建；2026-09-15 已重新执行完整 Maven `clean verify`，242 项失败/错误/跳过均为 0。

## 历史问题记录

以下条目保留早期复查证据；移动端溢出、邮箱接口权限、自动隐藏与双层账户布局问题已按上方最新复验修复或重新验证，当前剩余边界以本页顶部和功能边界为准。

## 确认的问题

后续邮箱修复：已重新构建并加载包含邮箱服务的后端，登录后的邮箱状态查询现已通过真实 HTTP 回归；第 2 项运行接口缺口已解决。前端账号邮箱入口也已补齐，独立保存、密码清空、真实确认及刷新持久化浏览器回归通过；发信请求拦截、挑战由数据库夹具提供，真实收件箱闭环仍未验收。另修复限流命名空间长度超限，确保请求/确认能进入服务而非抛出内部异常。参见 account-email.md 的最新证据。

1. **移动端顶部溢出**：390×844 首页，documentElement.scrollWidth=406，大于视口 390。截图可见管理员名称被挤成竖排、退出按钮被截断。独立新浏览器上下文的分区布局也可复现；此前双层布局缩窄窗口同样检测到溢出。证据：`.runtime/review-mobile-home.png`。
2. **邮箱验证未形成可用闭环**：源码已有 AccountEmailController/Service/Store 和测试，但登录后当前实例 GET /api/account/email 返回 403。需核对实际加载版本和权限配置，不能将源码存在当作运行验收，也不能直接放宽权限解决。前端仍没有邮箱验证编辑/确认入口。
3. **文档完成状态滞后**：current-state-review-2026-09-09.md 与 account-email.md 的部分段落仍描述早期 SMTP 或内部存储阶段。SMTP 页面、传输、队列和查询已存在；邮箱服务代码也已存在。以代码、当前运行结果和本报告的验证范围为准。

## 功能边界

| 范围 | 已有能力 | 尚缺或尚未验收 |
| --- | --- | --- |
| 账号 | 登录退出、密码、资料、头像、会话管理、邮箱与手机验证 | 外部收件箱/短信供应商端到端验收、登录/IP/设备事件及日志保留策略 |
| 权限管理 | 用户、角色、组织及核心数据范围 | 各资源与委派场景的全面验收 |
| 导航外观 | 双布局、内部组、自动隐藏/固定、平滑动效、文字切换、主题、八语言 | 触控设备、跨标签偏好同步和全部页面组合仍需扩大验收 |
| 配置字典 | 品牌、协议、水印、维护、安全策略、资料字段、字典与版本历史 | 生产外部发布、跨实例缓存和大数据量验收 |
| 文件 | 私有上传下载、配额、清理及头像 | 生产共享/对象存储、共享附件引用和代理链路 |
| 模块 | identity/platform 两个静态注册模块 | 动态菜单、模块安装升级禁用 |
| 邮件任务 | SMTP 配置、加密凭据、内部 TLS 传输、持久队列、消费与状态查询 | 外部收件箱验收、不确定结果人工处理、保留策略、通用任务平台 |
| 交付运维 | 本地服务健康、编译、生产构建、API/schema/browser 回归和测试脚本 | 远端 CI、部署验证、备份恢复、容量压测和故障切换 |

官网和赛事业务尚未形成迁移闭环，与公共脚手架验收分开统计。脚手架目录及 CI 文件仍未被 Git 跟踪，本次未提交或部署。

后续应补齐动态模块和外部渠道验收，再执行全流程及生产交付验收。上文编号问题保留为历史证据，当前修复状态以本页最新复验及对应“后续修复”段落为准。

## 最新复查：账号能力与完成边界

- 邮箱编辑/验证和登录页找回密码均已有前端、API、持久化及回归。找回密码另有真实本地 TLS 邮件接收代码后完成重置的集成测试；它不等于外部收件箱送达，也不等于单一浏览器到外部邮箱的端到端验收。
- 个人资料新增安全记录抽屉，复用分页表格和抽屉。仅呈现本人密码、邮箱验证及会话撤销事件，返回事件编号、动作和时间；不返回会话标识。旧同名账号会话事件按当前账号创建时间排除。密码更新、会话注销和审计写入在同一事务中。
- 本次安全记录浏览器复查通过：事件翻译、刷新失败保留记录、恢复刷新、390px 页面宽度及关闭后保留父抽屉。登录 HTTP 回归重新通过。后端现有 Surefire 报告为 160 项，失败/错误/跳过均为 0；前端重新执行类型检查、12 项测试及生产构建通过。安全记录 HTTP 回归也重新通过，覆盖分页、字段边界、跨账号隔离、旧同名账号排除和密码/会话/审计事务回滚。
- 仍未完成：动态模块安装升级及可安装组件包、可信设备识别、邮件/短信外部渠道送达与不确定结果人工处理、生产共享文件存储，以及远端 CI、备份恢复、容量压测和故障切换验收。审计保留已有默认实现，生产保留周期与删除审批仍需按合规要求确认。
- 15 个页面加载正常及专项测试通过，不代表全部按钮、权限组合、自动隐藏动效和每种手机布局均已完成验收。当前不能宣布全量迁移完成或生产高并发、高可用达标。

登录事件后续补齐：已将实际认证成功、密码错误、限流拒绝和超长密码预过滤拒绝绑定到账号 ID，接入个人安全记录及八语言动作名称；未知账号不持久化输入名称。数据库专项测试与真实 HTTP 登录/失败事件、限流边界、分页、隔离检查通过。IP/设备信息仍以受信来源地址和客户端标识呈现，不等于可信设备识别。

登录来源后续补齐：新增来源地址和客户端标识，复用受信代理解析，限制并清理客户端标识，以文本呈现；旧记录允许为空。登录拒绝事件和审计有界保留已接入，完整 clean verify 242 项、前端 17 项测试/类型检查/生产构建，以及加载 V29 后的真实接口字段、隔离和回滚回归均通过。客户端标识不等于可信设备识别；生产合规周期和容量验收仍未完成。

移动抽屉专项修复：截图发现宽抽屉左侧裁切，即使 documentElement 无溢出也会发生。共享 PlatformDrawer 已限制 wrapper 最大宽度，并新增对话框矩形边界断言。390px 截图复核通过，关闭入口完整可见；宽表格在抽屉内横向滚动。

待继续定位：账户键盘回归首次在手机双层布局修改密码窗口按 Escape 未关闭，加入失败诊断后复跑四组均通过，未修改关闭行为。该偶发问题不能据复跑通过认定已修复。

Escape 后续修复：压力回归第七轮复现，焦点位于会话抽屉关闭按钮，账户菜单已隐藏但 Escape 未关闭抽屉。已在共享组件处理来自当前抽屉、尚未消费的按键，保留嵌套弹层、keyboard=false 与输入法保护。修复后八轮手机双层压力回归及默认四组键盘回归通过；输入法和下拉框优先关闭也通过。前述偶发失败的已复现场景现已修复，全站弹层仍需按范围验收。

模块体系进展：已新增内部模块状态及修订历史、核心保护、可选模块默认停用、依赖图验证、乐观版本及事务审计。真实数据库并发/回滚测试包含在 168 项 clean verify 通过结果中。管理写 API、可选模块运行边界、导航/API 联动和安装升级仍未完成，当前页面还不能操作启停。

模块管理接口进展：已提供真实状态读取和受写权限/全局范围约束的状态 PUT 接口，代码版本与状态版本分离。171 项后端测试、运行版本权限/CSRF/核心保护/边界/撤权 HTTP 回归及旧登录回归通过。当前仍仅有核心模块，前端启停、可选模块执行约束及安装升级未完成。

模块 HTTP 访问控制进展：已加入 handler 归属声明、启动时注册验证和请求拦截，停用/缺失的可选模块状态返回 503。MVC 专项验证停用时控制器不执行、启用恢复、再次停用立即阻断。当前只有核心模块，实际可选功能拆分、后台任务约束和前端联动仍未完成。

首个可选模块闭环：审计查询已拆出并接通前端启停、必填原因、版本冲突、模块权限和菜单/直达页/接口联动。真实 HTTP 与跨标签页浏览器回归通过，停用不影响核心审计写入、登录及个人安全记录。其他模块拆分、任务约束、安装升级及生产验证仍未完成。

模块历史验收：全局模块读取权限约束的历史接口和共享分页抽屉已接通。真实数据库覆盖 105 条记录排序、分页及模块隔离；HTTP 覆盖字段、参数、空记录和 SELF/ALL 权限。桌面操作与 390px 抽屉回归、截图复核、生产构建通过。最新后端报告 177 项，失败/错误/跳过均为 0；模块安装升级及其他公共功能迁移仍未完成。

导航动效专项验收：新增 navigation-motion-browser-smoke.mjs 并纳入 test:browser。1213×886 双层自动隐藏下连续切换两主栏目 8 轮，requestAnimationFrame 采样确认同一面板节点持续可见且 opacity=1；展开/关闭含中间帧，关闭透明度单调下降。固定状态刷新保持、减少动态效果关闭过渡、无 pageerror 均通过。该证据覆盖上述桌面场景，其他设备、触控与全部键盘路径仍需独立验收。

公共草稿进展：服务端读取、保存、删除已适配，加入账号隔离、版本冲突、配额和事务审计；数据库专项及真实 HTTP 回归通过。八语言审计动作已接入。前端草稿恢复/保存和公共表单闭环仍待实现。

### Shared draft UI verification

Organization creation now consumes the shared account-draft hook and DraftActions component. It supports manual save, confirmed restore/delete, conflict reload and revision-specific cleanup after successful creation. Field codecs exclude record versions/permissions and reject incompatible payloads. Eight-language messages are complete.

Browser coverage passed for reload persistence, cross-tab conflicts, preserved current input, stale cleanup preserving another tab's newer draft, successful cleanup of the consumed revision, and 390px drawer bounds/screenshot. Frontend typecheck, 15 tests and production build passed. The browser script is registered in test:browser. Other public form adapters and full platform migration remain open.

### Account draft management completed

Added owner-only paginated metadata listing and the account draft drawer, reusing ResourceTable, PlatformDrawer and DeleteAction. Search, pagination and revision-checked deletion are available from tree desktop, mobile and dual-navigation account entries. Draft content is not returned in the list. Stale deletion preserves data and refreshes the list instead of silently retrying.

Eight database draft tests, running HTTP pagination/field/isolation checks, and browser list/search/stale-delete/delete/entry/mobile checks passed. Typecheck, 14 frontend tests and production build passed. Other public form adapters and broader platform migration remain open.

### Feedback adaptation

The reusable feedback capability now has a public platform adapter: V34–V38 storage, reserved dictionary policy, optional-module state, independent read/create/manage grants, owner and global list/detail scopes, sanitized rich text with shared normalized image references, versioned processing and transactional audit. V43 adds the feedback creation account-draft adapter with schema validation, restore-after-cancel and consumed-revision cleanup. V45 adds the sensitive-word policy module with an immutable matcher snapshot and feedback blocking before persistence. V46 adds the versioned runtime localization catalog, import/publish boundary, public bundle and static fallback. V47 adds encrypted SMS delivery and account phone verification. The Umi pages reuse the shared table/drawer/editor primitives and eight-language catalog. Live HTTP and browser smokes cover image upload, authorization, deletion protection, draft save/restore/cleanup, sensitive-word check/block/status/import, localization create/publish/public-bundle, SMS unavailable feedback and phone drawer bounds; the latest clean Maven run reports 242 tests with zero failures, errors or skips. See feedback-migration.md for the retained contract and evidence.
