# 数据库初始化与维护

Lumira 新脚手架使用 PostgreSQL 18.6（常说的“大象数据库”），默认数据库名为 `lumira_next`。具体实例由环境连接配置决定。应用数据库独立于旧系统，不连接旧 MySQL，也不把旧库的数据混入新库。生产环境可以把同一 PostgreSQL 基线部署到托管 PostgreSQL，但需要另外配置高可用、备份和故障切换。

可以用下面的查询确认当前实例，而不要根据客户端图标或目录名称判断：

```sql
SELECT version(), current_database(), current_user, NOT pg_is_in_recovery() AS writable_primary;
```

查询结果应与目标环境的数据库版本、名称、角色及读写状态一致。

## 单文件初始化

新建数据库只需一份最终态初始化 SQL：

`backend/src/main/resources/db/migration/B52__platform_initialization.sql`

B52 是当前全局数据库的唯一初始化 SQL，直接创建完整对象。它包含：

1. 数据库函数、表、检查约束和视图
2. 模块、字典、导航、认证策略和权限兼容映射的稳定种子
3. 主键、唯一键、外键及分页、租约、软删除和检索索引
4. 文件归属和敏感词修订触发器

种子先于写入触发器导入，避免初始化改变修订号；时间和随机修订标识已规范为固定值。用户、会话、文件、工单和敏感词条等业务表保持空表。

初始化 SQL 不包含账号、管理员密码、邮箱/短信凭据、会话数据或业务数据。B52 会预置“管理员”和“普通用户”两个角色：管理员角色持有平台权限，普通用户角色使用 SELF 数据范围并仅包含允许注册的反馈访问、读取和创建权限。服务启动后，`IdentityConfiguration` 只创建 `admin` 引导账号，并由角色适配器将该账号绑定到管理员角色；权限通过角色授予，不写入该账号的直授权限表。B52 不创建普通账号，注册策略默认关闭，缺省注册角色为普通用户。

本机 `scripts/init-local.mjs` 将初始管理员的随机引导密码写入权限为 0600 的 `.runtime/local.json` 中 `LUMIRA_BOOTSTRAP_PASSWORD` 字段。初始化包含初始管理员的持久改密要求表：该引导密码只能进入强制改密流程，不能建立正常登录。`POST /api/auth/login/password-change` 成功后会返回标准 `LoginResponse` 并继续当前登录流程；不需要其他步骤时直接进入已认证状态，需要 CAPTCHA、MFA 或其他策略步骤时则返回对应的未认证状态，完成这些步骤后再进入应用。服务会轮换会话 ID 和 CSRF token。要求完成后会留下数据库完成标记，服务重启或再次使用原引导配置时不会重新强制改密。刷新会恢复未完成的改密步骤；取消或 pending challenge 过期只结束该次登录步骤，不会解除要求。

已有安装首次启用该改密功能时只检查未软删除的 `admin` 账户：只有当前密码摘要仍匹配配置的引导密码，才会按初始管理员兼容规则处理；若留存的密码修改/重置审计或临时重置要求表明管理员已更换过密码，则写入已完成标记，不再强制使用旧引导密码。若现有密码摘要与配置的引导密码不匹配，则保留该账户当前密码，不按旧引导值推断为待改密。该兼容判定不会把配置值写入数据库或文档。

`.runtime/local.json` 保存的是原始引导值；管理员改密不会更新它。后续交互式登录以及需要管理员认证的本机 smoke 流程必须使用管理员当前实际密码。既有脚本不会因这次改密而自动获得新密码；请通过本机受保护的凭据配置提供当前值，不要把密码提交、发布或复制到其他环境。

旧版本增量 SQL、兼容升级目录和历史测试归档已删除。运行时只扫描 `classpath:db/migration`：空库执行 B52，已经通过 B52 初始化的数据库在重复启动时只校验，不重复创建对象或写入种子。模块归属由 `db/module-ownership.json` 的 `baseline`、`tableOwners` 和 `objectOwners` 登记。

本项目不再提供旧版本数据库升级。带旧版本迁移记录的数据库不能直接切换到此配置；应先备份并按下述显式重建流程初始化。清理仓库文件不会自动重置当前业务库或修改其记录，Flyway 校验保持启用。插件的安装与卸载迁移仍由各插件独立管理，不并入宿主全局初始化脚本。

## 已有 B52 本地库的保数据切换

B52 初始化文件的内容或 Flyway checksum 变化后，不能把新 artifact 直接指向使用旧 checksum 的现有数据库。Flyway 校验必须保持启用；不能执行 `flyway repair`、关闭校验、修改历史记录或把旧数据库重新 baseline 到新版本来绕过差异。本节描述的是保留原库的本地克隆方案，不是在线升级，也不会把旧库改造成新库。

安全切换需要先停止源后端并确认写入已冻结，生成受 ACL 保护的完整 `pg_dump --format=custom` 备份，再在**不存在的独立目标数据库**上使用当前 artifact 正常运行 Spring/Flyway，让新 B52 自己初始化。先核对目标 Flyway 版本/checksum 及源、目标 schema fingerprint；确认结构兼容后，再把备份中除全局 Flyway 历史以外的全部表数据和序列恢复到目标。不要清空或覆盖源库，源库及其原始备份都应完整保留。

只允许明确列出的角色/授权/注册默认值发生转换：将源库管理员原有的直授权限集合原样迁入管理员角色、绑定 `admin` 账号并仅移除该账号对应的直授行；不得用新空库管理员角色的权限集合扩充旧管理员权限，也不改其他用户的直授权限。保留注册策略原有的启用状态、渠道和其他设置；只有源 `default_role_id` 为空时才设为“普通用户”角色。密码摘要、认证凭据修订标记、改密要求、会话和其他业务/审计数据应逐表核对并保持不变。目标数据恢复后做逐表行数与内容指纹比较，只有上述授权关系与允许的注册默认值可不同。

克隆工具必须固定并校验源/目标数据库名、要求目标此前不存在、校验旧 B52 身份与新 artifact、拒绝占用中的源服务，并将可用完整备份写入权限检查通过的受保护私有运行时目录。实际备份路径由本机运行环境选择，不应假定项目工作区具备私密权限。此专用本地克隆流程在隔离 QA 和人工脚本审阅完成前不得用于当前业务库；如果任一版本、schema 或数据指纹检查不一致，应停止并保留源库与备份供调查，不要尝试 repair 或重建源库。

## 初始化方式

创建角色和数据库后，可以直接导入：

```sh
psql -v ON_ERROR_STOP=1 -1 -d lumira_next \
  -f backend/src/main/resources/db/migration/B52__platform_initialization.sql
```

也可以直接启动后端，Flyway 会自动执行 B52 一份初始化脚本。不要再次向已有对象的数据库导入 B52。手动导入使用单事务和遇错中止，避免留下半成品结构。

如果已经用 `psql` 导入 B52，首次启动需要让 Flyway 建立版本 52 的基线记录：

```sh
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true \
SPRING_FLYWAY_BASELINE_VERSION=52 \
node scripts/start.mjs
```

此方式只用于已经完整导入 B52、尚无 Flyway 历史记录的数据库。Flyway 登记版本 52 后不会重放初始化。默认不启用 `baseline-on-migrate`，避免对未知非空库自动认定已完成初始化；不能把旧版本数据库标记为 52 来代替重建。

本地重建使用受保护命令：

```sh
node scripts/stop.mjs
LUMIRA_ALLOW_LOCAL_DB_RESET=1 pnpm db:reinitialize
node scripts/start.mjs
pnpm db:schema-smoke
```

`db:reinitialize` 没有显式重置开关时会拒绝执行；执行前生成 `.runtime/backups/<database>-<timestamp>.dump`，只操作配置中的 `lumira_next`。旧系统数据库不会被访问。远程环境应通过 `LUMIRA_DB_ADMIN_*` 提供单独的数据库管理员连接，不能把管理员密码写进仓库。

## 本地启动与换库插件清单护栏

新克隆仍使用 `scripts/init-local.mjs` 生成的 canonical `.runtime/local.json` 和默认 `lumira_next`；不会扫描或合并历史 `.runtime` 配置。接管已有服务时，在 WSL 仓库根目录运行 `node scripts/local-runtime.mjs adopt --pid <PID>`。如果数据库客户端只能通过 Docker 调用，可加 `--psql-docker-container <容器名>`。命令会从已验证的本工作区 Java 后端进程读取配置，保留 canonical 中的其他字段，把旧 canonical 备份到仅当前用户可读的 `.runtime/backups/`，并将本机运行配置和当前后端 JAR 绑定到 `.runtime/local-runtime.json`；密码和密钥不会打印。历史 `.runtime` 配置文件保持原样且不会自动发现。接管后，启动、状态和插件清单校验都使用 canonical 配置及 pin；状态检查遇到配置、进程环境或插件清单漂移时会失败，且不会把后端报告为可信健康。启动、状态和停止命令分别为 `node scripts/start.mjs`、`node scripts/status.mjs`、`node scripts/stop.mjs`；本项目没有 `pnpm start` 入口。

pin、canonical 中的本机路径和 `.runtime/local-plugin-inventory.json` 仅供当前机器使用；不要加入 Git 或复制到另一台机器。新克隆应独立运行 `pnpm db:prepare` 生成自己的配置与数据库。已存在自定义数据库配置时，`init-local.mjs` 不会执行默认库的建库 SQL；应先 adopt 运行中的服务，或使用下述受护栏保护的切换流程。

首次接管后及有意安装、升级或启停插件后，先核对数据库与包目录，再运行 `node scripts/local-runtime.mjs capture`。已有清单发生插件变化时，更新必须带非空原因，例如 `node scripts/local-runtime.mjs capture --accept-changes "批准升级头像插件"`；旧清单会先备份。数据库 endpoint、名称、OID 或角色变化不能用 capture 重新认领，必须走换库流程。若只更新后端 JAR，使用 `node scripts/local-runtime.mjs refresh-artifact --jar <JAR路径> --sha256 <SHA256>` 显式刷新 artifact pin。

换库前停止当前服务并确保源、目标数据库没有其他客户端连接。先恢复目标库，再用一个 JSON 配置文件描述目标连接与当前工作区的 files/plugins 路径；该文件仅作为命令输入，不会替代 canonical。运行 `node scripts/local-runtime.mjs switch --target-config <目标配置>`。流程会确认源插件快照仍与 pin 精确匹配、目标数据库身份不同、目标插件 ID/版本/摘要/installed/explicitInstall/enabled 与源完全一致且双方制品完整；随后先备份，再逐个原子写入 canonical 配置、运行时 pin 和插件快照，最后重新验证。异常时会逐个尝试恢复所有原文件，并报告无法恢复的文件。该锁会串行化 `adopt`、`capture`、`switch`、`refresh-artifact`、启动及 `db:prepare`，阻止这些命令在更新期间交错；它不能让多个文件的更新具备断电原子性。进程在更新中断后，保留的 `.runtime/local-runtime.lock` 会让受锁命令失败关闭；先确认没有操作仍在运行并检查配置、pin、清单和备份，再移除该锁。启动和清单校验遇到不一致时会拒绝信任当前运行时。默认连接检查要求本地清单查询角色拥有 `pg_read_all_stats` 可见性；缺少权限时会拒绝切换。若 source 与 target 使用同一个本地 PostgreSQL Docker 容器，可显式增加 `--activity-audit-docker-admin true`，让容器的 `POSTGRES_USER` 仅以 `BEGIN READ ONLY`/`ROLLBACK` 查询连接活动；插件清单和数据库身份读取仍使用 canonical app role，app role 权限及 inventory identity 不变。该参数只接受小写 `true`，且只用于 source/target 的连接审计。护栏只读检查数据库，不会写插件状态或自动安装插件。若无法使用原生 `psql`，可在 canonical 本机配置中设置 `PSQL_BIN` 或 `LUMIRA_PSQL_DOCKER_CONTAINER`；这些工具选择不会固定在源码中。

## 后期维护规则

- 领域表按 `platform_*` 前缀归属平台模块；新增表要同时提供主键、必要的 `CHECK` 约束、明确的删除策略和与查询形态匹配的索引。
- 可变管理数据使用 `version` 乐观并发字段；字典、模块和内置导航使用固定 UUID/编码，重放初始化或恢复备份时不会产生随机种子差异。
- 敏感字段只保存加密密文或摘要。初始化文件和数据库备份都不应包含明文密码、验证码、SMTP/SMS 密钥。
- 软删除表必须在唯一索引中排除 `deleted=true`；队列表必须有状态、可用时间和租约索引，清理任务使用有界批次和 `SKIP LOCKED`。
- 任何写入都由领域服务完成版本校验和审计，数据库约束负责兜底，不能把权限判断交给前端菜单。
- 在隔离 PostgreSQL 18 空库验证 Flyway 首次初始化、重复启动和手动导入后的基线登记，并核对当前表、约束、索引和稳定种子。数据库功能测试从同一份初始化 SQL 提取对象定义，不维护历史升级测试或重复 DDL 归档。临时报告存入 `.runtime`；功能回归范围按实际影响选择。

当前发布只保留 B52 这一份初始化 SQL。数据库结构、模块归属、当前结构测试和初始化文档应保持一致。本次清理不重置本地业务数据库、不复制当前业务数据；重建必须使用上述备份和显式重置命令。

`pnpm db:schema-smoke` 会按脚本当前定义检查 SQL 文件、平台表、内置导航、稳定种子、关键唯一索引、密码摘要字段和数据不变量；具体覆盖范围以脚本实现为准。它不替代生产备份恢复演练、容量压测或故障切换验收。

插件数据库采用[单文件初始化契约](plugin-initialization.md)：每个内置插件只保留一份最终 SQL，旧记录按原身份和校验和验证，现有数据及历史不重放、不改写。
