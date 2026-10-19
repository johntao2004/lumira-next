# Codex 云端开发

此配置用于 Codex 云端的独立 Ubuntu 或 Debian 开发环境。数据库和管理员密码在云端生成；不复制本机 `.runtime`、数据库或上传文件。它不是生产部署配置。

云端开发还需要 Docker Engine 与 Docker Compose v2，并要求当前 VM 用户可访问正在运行的 daemon。安装入口会先检查这两项；它不会在 VM 中自动安装 Docker。请先在一次性 VM 安装并启动 Docker，使用同一用户确认 `docker info` 和 `docker compose version` 成功，再运行 Lumira 安装脚本。Office 解析不使用宿主 JVM 回退。

## 环境配置

先将需要继续开发的源码提交并同步到 GitHub。创建云端环境时选择对应仓库，安装脚本填写：

```sh
LUMIRA_CLOUD_DEV=1 bash scripts/cloud-install.sh
```

Start skill 填写：

```text
在仓库根目录运行 bash scripts/cloud-start.sh。
如果命令失败，检查 .runtime/backend.log 和 .runtime/web.log，修复后重试。
确认后端 http://127.0.0.1:8090/actuator/health/readiness 和前端
http://127.0.0.1:8010/user/login 均返回 HTTP 200，再开始依赖服务的开发任务。
后端改动后重新运行启动命令，让运行进程加载新 JAR。
```

允许联网，选择 Package managers，并添加以下下载域名：

- `nodejs.org`
- `maven-central.storage-download.googleapis.com`
- `api.adoptium.net`
- `github.com`
- `release-assets.githubusercontent.com`
- `ftp.postgresql.org`
- `packages.adoptium.net`
- `www.postgresql.org`
- `apt.postgresql.org`

安装器支持 Ubuntu 和 Debian。Debian 普通用户默认使用无需 sudo 的方案：工具链保存在仓库之外的 `/workspace/lumira-tools/linux`（其他 VM 使用 `$HOME/.local/share/lumira-cloud`，可用 `LUMIRA_CLOUD_TOOLS_DIR` 指定）；Node 24 和 Temurin JDK 25 下载包验证 SHA-256，PostgreSQL 18.6 从官方源码校验后编译，保留 zlib，省去开发环境不需要的 ICU 和 psql 行编辑。缺少的 flex、bison、m4 使用系统提供的官方发行版签名密钥，在工具目录内建立隔离 apt 索引、下载并解包；不读取受限的系统 apt 配置，不修改系统。VM 需提供 gcc、make、perl、python3、curl、tar、sha256sum、dpkg-deb 和 apt-get。Ubuntu 普通用户可加 `LUMIRA_CLOUD_USERSPACE=1` 使用同一方案；原有 Ubuntu root/免交互 sudo 方案仍使用官方签名 apt 源。pnpm 固定为 10.33.0。npm/pnpm 元数据缓存显式放在工具目录，并复用已有 workspace 的 pnpm store。Maven Wrapper 下载目录和依赖缓存显式放在工具目录，不依赖可写的 `~/.m2`。云端默认使用 [Google 官方 Maven Central 镜像](https://docs.cloud.google.com/docs/buildpacks/java#google_cloud_hosted_maven_central_mirror)，避免共享出口 Central 429；Wrapper 发行包固定 SHA-256。Maven 在 VM 提供 `/etc/ssl/certs/java/cacerts` 时使用现有系统 Java 信任库，保持 TLS 验证；不导入未经信任的证书。可用 `LUMIRA_CLOUD_MAVEN_MIRROR` 选择 `https://repo.maven.apache.org/maven2` 或 `https://repo1.maven.org/maven2`。每次加载 cloud-env 时从当前云端代理环境生成权限 0600 的 Maven settings，避免把临时代理凭据写入源码或固化在快照中。

云端独立 PostgreSQL 18 集群必须使用 5432；端口冲突或配置指向其他数据库时停止并报错，不重置数据库。普通用户集群位于 `.runtime/cloud-postgres`，只监听回环地址；Unix socket 目录权限 0700，TCP 使用 SCRAM 密码认证。应用配置和密钥保存在权限受限的 `.runtime/local.json`。新环境首次启动会自动应用 Flyway 迁移，并在确认没有旧密文时生成开发加密密钥。已有密码及密钥不会替换。

安装脚本执行前端类型检查、测试、构建和启用数据库集成测试的 Maven verify。Maven Office 格式回归通过真实的 1 GiB cgroup 限制 worker 执行；`scripts/office-worker-test.mjs` 先从当前源码重新 package 后端 JAR，再创建独立临时 worker/token 并在验证后停止，或使用成对传入的 `LUMIRA_OFFICE_WORKER_URL` 与 `LUMIRA_OFFICE_WORKER_TOKEN` 指向一次性 QA worker。启动脚本刷新锁文件依赖、构建当前后端，启动一个 Docker Office worker 和两个应用服务并检查健康；worker token 单独保存在 `.runtime/office-worker/`，不写入 `.runtime/local.json`。独立 worker Dockerfile 在 builder stage 从 Docker build context 中的当前源码构建 JAR，因此干净 checkout 不要求预先存在 `backend/target`；Maven Wrapper 会校验发行包 SHA-256。不要将安装时的构建结果当作新任务当前源码的验证结果。

## 开发任务命令

```sh
bash scripts/cloud-start.sh
bash scripts/cloud-check.sh
pnpm test:java
source scripts/cloud-env.sh
node scripts/status.mjs
python3 scripts/smoke.py
bash scripts/cloud-stop.sh
```

临时日志、截图和测试输出保存在 `.runtime`，不提交到仓库。云端的 `127.0.0.1` 是云端 VM 内部地址，不能作为本机浏览器可直接访问的公网预览地址。

安装和启动通过后，运行 `bash scripts/cloud-stop.sh` 正常关闭服务和数据库，保证文件系统快照一致，再在环境界面保存并发布；随后用一个新任务再验证启动。修改配置后重新发布，新任务才使用更新后的环境。

官方说明：[Codex Cloud environments](https://learn.chatgpt.com/docs/environments/cloud-environments)、[PostgreSQL Ubuntu 安装](https://www.postgresql.org/download/linux/ubuntu/)、[Temurin Linux 安装](https://adoptium.net/installation/linux/)。

## 干净工作区复现与验收

从需要验证的提交创建干净工作区，不依赖已删除的临时分支，也不复制旧.runtime或业务数据。以下步骤适用于同版本工具链；独立SDK构建入口以framework-development.md为准。

## 本机工具

准备与仓库锁文件一致的运行环境：

- Node.js 24.x；仓库 `engines` 限定为 24.x。
- pnpm 10.33.0；版本也固定在根 `package.json` 的 `packageManager`。
- Python 3，仓库的 module-boundary、数据库 smoke 与相关工具脚本通过 `python3` 运行。
- JDK 25，并在当前 shell 中设置 `JAVA_HOME`。
- PostgreSQL 18，且本机 `psql` 客户端可从 `PATH` 调用。`scripts/init-local.mjs` 支持用 `PSQL_BIN` 指定客户端命令；若清单检查使用 Docker 中的 psql，可在本机 canonical 配置中指定 `LUMIRA_PSQL_DOCKER_CONTAINER`。
- 已启动的 Docker Engine 与 Docker Compose v2。Office 预览和 `pnpm test:java` 管理的测试 worker 都需要可用的 Docker daemon。
- Playwright 可启动的 Chromium 和所需系统运行库；浏览器 smoke 脚本也接受 `BROWSER_EXECUTABLE` 指向机器已有的兼容浏览器。
- `zip` 命令行工具；插件构建脚本用它生成插件 ZIP。

在开始前确认版本和服务：

```sh
node --version
pnpm --version
python3 --version
java --version
psql --version
docker compose version
docker info
```

使用 macOS 或 Linux 时按该机器现有的工具安装和 shell 环境设置 `PATH`、`JAVA_HOME` 与 PostgreSQL 连接参数；仓库不固定任何机器的工具安装目录。`pnpm test` 中的 cloud fixture 要求以非 root 用户运行；若 WSL 默认登录 root，先切换到非 root 开发账号。日常本机验收直接运行项目脚本，不运行只供一次性 Ubuntu/Debian VM 使用的 `scripts/cloud-install.sh`。

## 构建和自动测试

从仓库根目录运行：

```sh
pnpm install --frozen-lockfile
pnpm exec playwright install chromium
pnpm check:java-format
pnpm typecheck
pnpm build
pnpm plugins:typecheck
pnpm plugins:build
node scripts/build-plugins.mjs --no-host sdk/examples/hotplug-probe
node scripts/build-plugins.mjs --no-host sdk/examples/runtime-observer sdk/examples/verification-debugger
pnpm test
pnpm test:java
node --test tests/local-runtime-config.test.mjs tests/local-plugin-inventory.test.mjs tests/local-runtime-cli.test.mjs
```

`pnpm plugins:build` 默认只构建 `plugins/` 下一层含 `plugin.json` 的正式插件；它不会递归纳入 `plugins/sdk/examples/`。`hotplug-probe` 是插件生命周期 smoke 使用的 fixture，需按上面的定向命令单独构建；最后一条定向命令为验收额外生成另外两个示例包。插件 `dist/`、JAR 和 ZIP 是构建输出，不作为源码提交。

`pnpm test:java` 即使成功退出，也可能因缺少专用数据库环境变量而跳过数据库集成测试。宿主当前结构验收使用唯一的 B52 初始化基线；独立的插件迁移测试仍按插件自己的 Flyway 历史运行。按需要为下表中的测试设置各自的 `*_TEST_DB_URL`、`*_TEST_DB_USER` 和 `*_TEST_DB_PASSWORD`，并将连接信息放在仅当前用户可读的私有 runtime 环境文件或测试进程环境中：

| 环境变量前缀 | 新建数据库名必须以前缀开头 | 迁移范围 |
| --- | --- | --- |
| `LUMIRA_DATABASE_BASELINE_TEST_DB_` | `lumira_qa_database_baseline_` | B52 首次应用、手工导入、基线登记与重复运行幂等性 |
| `LUMIRA_MIGRATION_TEST_DB_` | `lumira_api_docs_qa_` | B52 首次应用及 API 文档、监控导航种子 |
| `LUMIRA_FEEDBACK_PERMISSION_MIGRATION_TEST_DB_` | `lumira_feedback_permission_qa_` | feedback 插件 V2 至 V4 迁移 |
| `LUMIRA_PLUGIN_PERMISSION_FINALIZER_TEST_DB_` | `lumira_plugin_permissions_qa_` | 宿主 B52 上插件权限 finalizer 的独立迁移 |

`DatabaseBaselineMigrationTest` 还要求设置 `LUMIRA_QA_RUN_TOKEN`，并确保它与 `qa_guard.lumira_qa_fixture_guard` 中唯一的 `id=true`、`purpose='isolated-security-qa'` 标记行匹配。该测试会在开始和清理时删除并重建 `public` schema，因此目标必须是符合 `lumira_qa_database_baseline_<suffix>` 命名的专用数据库；不能使用 `lumira_next`、默认本地库或其他 QA 库。测试验证 Flyway 从空 schema 只应用 B52、`validate` 和重复 `migrate` 不改变结构或稳定种子；随后手工导入同一 B52 SQL，验证结构/种子一致、Flyway 只登记版本 52 基线而不重放 SQL，再次运行仍保持不变。其他三组也分别使用独立的新数据库，且 `public` schema 中不能已有 `flyway_schema_history`。不要将这些测试库连接信息提交到版本库；运行 `pnpm test:java` 后查看 Surefire 中对应测试类的执行与跳过状态。

有些验收还要使用新建的本地数据库和正在运行的应用：

```sh
pnpm db:prepare
node scripts/start.mjs
node scripts/status.mjs
pnpm db:schema-smoke
node scripts/stop.mjs
node scripts/init-secret-key.mjs
node scripts/start.mjs
node scripts/status.mjs
```

首次运行 `pnpm db:prepare` 会在 `.runtime/local.json` 生成独立数据库连接密码和管理员引导密码，并通过 `psql` 创建 `lumira_next` 角色和数据库。`PSQL_BIN` 只选择 psql 客户端；`init-local.mjs` 的管理连接使用继承的 libpq 环境，新文件中的 `LUMIRA_DB_URL` 固定为 `jdbc:postgresql://127.0.0.1:5432/lumira_next`，且脚本不读取 `.env` 或 `LUMIRA_DB_URL` 环境变量。让 PostgreSQL 在该默认本机地址可用。不要覆盖已有 `.runtime/local.json` 或已有数据库。新克隆应使用空的 `lumira_next` 数据库，不搬运旧机器业务数据。

`pnpm db:schema-smoke` 直接读取 `.runtime/local.json` 指向的数据库，只执行检查用的 `SELECT`，并核对迁移、表、约束、索引和基线种子；应在首次启动已应用迁移后运行。这个只读脚本与使用共享 `smoke_support.py` 的 fixture/API smoke 不同，不需要 QA namespace marker。`pnpm db:prepare` 不会创建 guarded smoke 使用的数据库，也不会生成 QA token 或 marker。

首次启动先应用数据库迁移。停服务后运行 `node scripts/init-secret-key.mjs`，脚本会在确认本地数据库没有已有加密密文后生成开发密钥并写入 `.runtime/local.json`，随后重启以加载密钥。没有该本地密钥时，短信等加密凭据功能会保持不可用；脚本只用于本机开发库，不用于生产或迁移已有密文的数据库。

## 隔离 QA 自动 smoke

`pnpm test:module-packages`、`pnpm test:plugin-data`、`pnpm test:browser` 及直接使用 `scripts/smoke_support.py` 的 API/fixture smoke，必须运行在专用的隔离 QA 数据库中。共享辅助模块会拒绝默认 `.runtime/local.json`，要求数据库名使用 `lumira_security_*`、`lumira_qa_*`、`lumira_test_*` 或 `lumira_smoke_*`，并要求显式 owner-only 私有配置（POSIX mode `0600` 或更严格）、配置中的 UUID `LUMIRA_QA_RUN_TOKEN` 与该数据库唯一的 `public.lumira_qa_fixture_guard` 标记行匹配。标记用途必须是 `isolated-security-qa`。浏览器 guard 还会从 `BASE_URL`（以及配置时的 `LUMIRA_BACKEND_BASE_URL`）获取匿名 session，并核对它属于同一标记数据库；两者必须是 loopback origin。

目前仓库提供 guard 和 marker 契约说明，但没有可移植的一键 QA 数据库/config provision helper。使用本机批准的 PostgreSQL 流程创建符合命名空间的专用空数据库和账号，再准备 owner-only 私有服务配置与 smoke 配置；配置至少要让服务与 smoke helper 使用相同的 JDBC URL、数据库账号和密码，并为需要登录管理员的 smoke 提供同一份新库引导密码。不要把 QA 凭据或 token 写入 Git。`scripts/init-local.mjs` 只初始化固定的 `lumira_next` 角色和数据库，不能选择 QA 库。已 pin 的本地服务应通过 `node scripts/local-runtime.mjs switch --target-config <目标配置>` 切换 canonical；该流程要求源快照已 pin、服务已停止、目标库已准备好，且源/目标插件清单和制品完全一致。它不会创建数据库或补装插件。新克隆尚无 pin 时，不要手工改写 canonical 绕过护栏；先用本机批准的流程准备并接管已运行的本地服务，再使用受验证的切换流程。若无法满足这些条件，就跳过 guarded smoke，而不是改用开发库。

QA 库第一次启动时必须保持 `public` schema 为空：先用指向该库的服务配置启动应用，等待 Flyway 完成基线迁移和应用 readiness 成功，然后才创建 `public.lumira_qa_fixture_guard` 表并插入唯一 marker 行。Marker 是运行验收用的数据库状态，不是应用迁移；若在首次 Flyway 启动前创建该表，Flyway 会把非空且没有迁移历史的 schema 拒绝为错误状态。marker 的 `run_token` 必须与私有 smoke 配置中的 `LUMIRA_QA_RUN_TOKEN` UUID 一致。`docs/qa-fixture-guard.md` 列出 marker DDL 与 Node guard 覆盖的浏览器脚本。随后用 `LUMIRA_SMOKE_CONFIG` 指向该私有 smoke 配置，并按测试要求设置 `BASE_URL` 与可选的 `LUMIRA_BACKEND_BASE_URL`；guard 会获取匿名 CSRF session 并确认 session 属于同一个已标记数据库。守卫未通过时先修正隔离 QA 配置，不要移除或改弱检查。

`pnpm test:module-packages` 和 `pnpm test:plugin-data` 会在生命周期操作期间轮询后端 `/actuator/health/readiness`。若 `BASE_URL` 指向 Umi 前端（`scripts/start.mjs` 默认端口 `8010`），必须显式设置 `LUMIRA_BACKEND_BASE_URL` 指向连接同一 QA 数据库的后端（默认端口 `8090`）；Umi 只代理 `/api`，不代理 Actuator readiness。若 `BASE_URL` 本身就是该 QA 后端 origin，则可省略 `LUMIRA_BACKEND_BASE_URL`。配置该变量时，匿名 session binding 也会验证后端 origin 属于同一个已标记数据库。

`pnpm test:browser` 包含历史脚本和共享 guard 脚本，整组运行时必须确保服务配置与 smoke 配置指向同一个专用 QA 数据库；不要只设置 smoke 配置而让正在运行的服务继续连接 `lumira_next`。新机器尚未准备这种 QA 环境时，可完成核心构建、`pnpm test`、Java worker 测试、`db:schema-smoke` 和手动 UI 流程，但应跳过这些 guarded smoke，而不是改用开发库。

`scripts/start.mjs` 使用当前 Node 24 与 `JAVA_HOME`，要求 `backend/target/` 中恰有一个后端 JAR；它启动默认的 8090 后端、8010 前端和隔离的 Office worker。已有 pin 时，它按 canonical 固定端口和制品运行，并在启动前后校验数据库/插件清单；开发预览要等 `/umi.js` 与 `/umi.css` 返回 HTTP 200。启动成功后，必须在 WSL 中运行 `node scripts/status.mjs`，确认 pin、运行环境、插件清单和 HTTP readiness 均通过。开发预览完成首轮编译后，再确认 `http://127.0.0.1:8010/__umi/api/bundle-status` 报告 `done: true`。最后通过浏览器登录并执行下面的手动流程；构建、HTTP 健康和浏览器验收是不同检查，不以其中一项代替其他项。

保存临时测试结果到 `.runtime/github-handoff/source-review/`，不要把截图、日志或机器配置放到 `docs/` 或源码根目录。停止本地服务使用：

```sh
node scripts/stop.mjs
```

## 插件和权限验收

显式构建后，模块管理导入以下本地 ZIP，并按安装预检提示安装、启用：

- `plugins/sdk/examples/runtime-observer/dist/runtime-observer-1.0.2.lumira-plugin.zip`
- `plugins/sdk/examples/verification-debugger/dist/verification-debugger-1.0.6.lumira-plugin.zip`

用 QA 角色分别检查每个权限。运行展示屏的 `platform:runtime-observer:access` 页面权限与 `platform:runtime-observer:read` 操作权限分开授予；页面每块数据还要求相应宿主全局读取权限，如监控、模块或事件队列读取。验证调试器的页面访问权限与 `read`、`control`、`fixture` 三项操作权限独立保存：查看会话/消息需要 `read`，创建或结束会话需要 `control`，协议 fixture 需要 `control` 和 `fixture`。检查只授予页面或只授予操作时，另一类请求不会被隐式放行。

访问运行展示屏，确认缺少宿主来源权限的区域显示无权限/未知，授权相应宿主只读权限后才显示该来源。访问验证调试器，分别创建和结束会话、读取私有收件箱，并运行成功、鉴权失败、限流、超时和格式错误 fixture。停用插件后确认导航和前端注册撤下、API 不再提供插件能力；再次启用后按当前 manifest 恢复。

## 短信 OTP 本地 QA

只在新建的本机数据库或专用 QA 数据库执行。不要在生产环境、共享环境或带有需要保留短信配置密钥的数据库上切换 webhook。示例插件默认关闭；启动前仅在本机进程环境设置调试开关：

```sh
LUMIRA_SMS_DEBUG_ENABLED=true node scripts/start.mjs
```

`prod` 和 `production` profile 会强制关闭调试接收器。模块管理启用验证调试器后，为当前 QA 登录人创建一个会话，并在当前页面临时查看会话生成的 endpoint 与 bearer token。会话有效 10 分钟，消息在内存中最多保留 5 分钟。

在此隔离数据库里，通过同源、带正常登录和 CSRF 的管理员设置请求临时更新 `/api/platform/settings/sms`：读取当前 `version` 后保存兼容的 `provider: "webhook"` 配置，将 endpoint、token、sender 和固定模板设为当前调试会话提供的值。短信设置页面不再提供 webhook 选项，但后端保留了旧 webhook 合约。验证码模板必须是 `Lumira verification code: {code}`，发送目标只使用示例要求的合成号码 `+15555550123`；不要使用真实手机号、云短信凭据或外部 URL。调试器只收本地请求，不调用云服务。

按当前认证测试数据完成并记录五类 OTP 往返：登录、公开注册、短信找回密码、账号手机号绑定、敏感操作重新验证。每次都确认原身份服务生成/校验 OTP，调试器只接收并展示已发送的验证码；检查成功路径和错误/过期路径。宿主认证/授权契约另由自动测试覆盖；这些测试不证明真实短信服务商投递。

测试结束后，先把本机短信设置恢复为测试前记录的**未启用、未选择供应商**状态并清除本次临时 webhook 凭据，再结束调试会话、停用示例插件并停止服务。对新数据库的默认空配置，可保存 `enabled: false`、空 provider/endpoint/sender、原默认模板并把凭据操作设为 `CLEAR`。不要尝试从读取 API 导出旧凭据；它们被设计为不可读取。若无法安全恢复原配置，就丢弃此专用 QA 数据库并重新创建，不要把指向已关闭 loopback endpoint 的 webhook 留在配置中。

## 验收边界

插件 fixture 和验证调试器是协议/界面测试工具。它们没有验证云短信账号、真实短信送达、商户回调、资金流或支付沙箱；支付插件的商户配置和回调也须由独立授权的支付沙箱流程验证。浏览器通过后再记录为 UI 验收，单纯的单元测试、构建成功或 HTTP 200 不代表浏览器流程通过。
