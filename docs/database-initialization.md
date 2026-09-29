# 数据库初始化与维护

Lumira 新脚手架使用 PostgreSQL 18.6（常说的“大象数据库”）。当前开发机运行的是 Homebrew 本地 PostgreSQL 实例，数据库名为 `lumira_next`；它不是 ElephantSQL 云服务。应用数据库独立于旧系统，不连接旧 MySQL，也不把旧库的数据混入新库。生产环境可以把同一 PostgreSQL 基线部署到托管 PostgreSQL，但需要另外配置高可用、备份和故障切换。

可以用下面的查询确认当前实例，而不要根据客户端图标或目录名称判断：

```sql
SELECT version(), current_database(), current_user, NOT pg_is_in_recovery() AS writable_primary;
```

本地当前结果为 PostgreSQL 18.6、数据库 `lumira_next`、角色 `lumira_next`，并且是可写主库。

## 基线与追加迁移

生产 Flyway 目录保留不可变基线和经过模块归属登记的追加迁移：

`backend/src/main/resources/db/migration/V1__platform_initialization.sql`

`backend/src/main/resources/db/migration/V2__identity_argon2_password_hash.sql`

`backend/src/main/resources/db/migration/V3__group_platform_navigation.sql`

V1 是原 V1–V48 增量脚本的最终结构合并版，按以下顺序组织：

1. 数据库函数和视图依赖的例程
2. 按领域分组的表与检查约束
3. 主键、唯一键和外键
4. 服务端分页、租约、软删除和检索索引
5. 文件归属触发器
6. 模块、字典和导航的稳定种子

初始化 SQL 不包含管理员密码、邮箱/短信凭据、会话数据或业务数据。服务启动后，`IdentityConfiguration` 使用本地配置的 `LUMIRA_BOOTSTRAP_PASSWORD` 创建第一个管理员，并授予完整的平台管理权限。

V2 由 identity 模块拥有，只扩大 `platform_user.password_hash` 到 256 字符，以容纳带参数版本标识的 Argon2id 摘要；它不重写现有摘要，也不包含任何秘密。V3 由 navigation 模块拥有，为用户管理、内容管理和运营功能创建内置目录，并把平台配置入口收拢到系统设置目录；页面路径、权限、模块状态和启用状态保持不变。V1 的表/视图/函数归属和追加迁移的归属分别由 `db/module-ownership.json` 校验。

原增量内容压缩在 `backend/src/test/resources/legacy-migrations.archive`，只用于验证旧库升级行为的回归测试；Flyway 默认不会读取该归档文件。新环境执行 V1 后再执行当前目录中的追加迁移。

## 初始化方式

创建角色和数据库后，可以直接导入：

```sh
psql -d lumira_next \
  -f backend/src/main/resources/db/migration/V1__platform_initialization.sql
```

也可以直接启动后端，Flyway 会自动按版本顺序执行全部迁移（当前 V1–V17）。两种方式只能选择一种，避免在已有表上重复执行 DDL。

如果已经用 `psql` 导入，首次启动需要让 Flyway 只建立基线记录而跳过已导入的 V1：

```sh
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true \
SPRING_FLYWAY_BASELINE_VERSION=1 \
node scripts/start.mjs
```

此方式会把已导入的 V1 标记为已执行，然后正常执行 V2 及之后的全部迁移；不能把 `BASELINE_VERSION` 设为 2 来跳过身份字段迁移。

本地重建使用受保护命令：

```sh
node scripts/stop.mjs
LUMIRA_ALLOW_LOCAL_DB_RESET=1 pnpm db:reinitialize
node scripts/start.mjs
pnpm db:schema-smoke
```

`db:reinitialize` 没有显式重置开关时会拒绝执行；执行前生成 `.runtime/backups/<database>-<timestamp>.dump`，只操作配置中的 `lumira_next`。旧系统数据库不会被访问。远程环境应通过 `LUMIRA_DB_ADMIN_*` 提供单独的数据库管理员连接，不能把管理员密码写进仓库。

## 后期维护规则

- 领域表按 `platform_*` 前缀归属平台模块；新增表要同时提供主键、必要的 `CHECK` 约束、明确的删除策略和与查询形态匹配的索引。
- 可变管理数据使用 `version` 乐观并发字段；字典、模块和内置导航使用固定 UUID/编码，重放初始化或恢复备份时不会产生随机种子差异。
- 敏感字段只保存加密密文或摘要。初始化文件和数据库备份都不应包含明文密码、验证码、SMTP/SMS 密钥。
- 软删除表必须在唯一索引中排除 `deleted=true`；队列表必须有状态、可用时间和租约索引，清理任务使用有界批次和 `SKIP LOCKED`。
- 任何写入都由领域服务完成版本校验和审计，数据库约束负责兜底，不能把权限判断交给前端菜单。
- 修改基线前先用 `pg_dump --format=custom` 备份，并在临时 PostgreSQL 18 空库执行 `psql -v ON_ERROR_STOP=1 -f ...`；随后运行 `pnpm db:schema-smoke`、Maven、前端和浏览器回归。

当前脚手架保持“不可变 V1 基线 + 小步追加迁移”的边界。未来若已有生产库需要在线升级，不能覆盖已经执行的 V1：必须新增经过评审、带模块归属和回滚/恢复说明的版本迁移。若环境允许停机重建，也可以备份、重建数据库并按完整 Flyway 目录重新导入。这样既保持新环境可复现，又不会让在线升级依赖破坏性重建。

`pnpm db:schema-smoke` 会验证活动 SQL 文件（V1–V17）、37 张平台表、34 条内置导航、稳定种子、关键唯一索引、密码摘要字段和数据不变量；它不替代生产备份恢复演练、容量压测或故障切换验收。
