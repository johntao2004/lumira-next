# 数据库部署角色与密钥

## 当前职责

新卷使用三个不同身份和不同密码：

- `lumira_bootstrap`：官方 PostgreSQL 首次初始化和 DBA 管理，仅数据库容器接收该密码。
- `lumira_migrator`：非超级用户，拥有应用 `public` schema 与对象。宿主全局 Flyway 和插件初始化显式使用该连接执行 DDL。
- `lumira_next`：非超级业务身份，默认 DataSource、JdbcClient、事务和插件业务连接使用此身份。具有应用表 SELECT/INSERT/UPDATE/DELETE、序列 USAGE/SELECT、schema USAGE 和本库连接/临时表权限，不拥有对象、不能对 public 持久对象 CREATE/ALTER/DROP/TRUNCATE，不能 SET ROLE 为迁移身份。

迁移身份的默认授权使后续插件表自动获得业务 DML 权限。每次全局或插件迁移结束（包括失败清理）撤销业务角色对对应历史表的写权限，保留 SELECT，并复核经 PUBLIC/组继承后的有效写权限；残留时阻断并要求 DBA 修复，不擅改其他组。迁移记录的所有权和写入权限属于迁移身份。现有 SQL 文件及历史校验和不变。

`LUMIRA_DB_REQUIRE_UNPRIVILEGED=true` 在全局迁移前校验两种连接的直接/间接集群特权，并拒绝业务连接的数据库 CREATE、public CREATE 和 public 对象所有权。任一查询失败即阻止启动。生产不能关闭此检查绕过旧卷升级。

这是默认 SQL 权限隔离。**宿主进程仍持有迁移连接配置，插件是同进程受信 Java 代码；本设计不构成恶意插件沙箱。** 只批准审核过的插件。开发模式未配置迁移身份时兼容原单连接；强制生产模式不允许此回退。

## 新卷

分别设置 `LUMIRA_DB_BOOTSTRAP_PASSWORD`、`LUMIRA_DB_MIGRATION_PASSWORD`、`LUMIRA_DB_PASSWORD`。Compose 将 `deploy/database/10-application-role.sh` 挂到官方初始化目录，仅空卷执行。脚本不修改已有身份，不把密码写入 SQL 文件或 Git。

server 显式接收持久化 `LUMIRA_SECRET_KEYS` 与 `LUMIRA_SECRET_ACTIVE_KEY`（默认 primary）。密钥环格式、长度和激活项仍由 SecretKeys 校验。保留旧密钥以解密历史数据，与数据库备份分开保管；不能生成替代密钥伪装恢复。

## 已有卷与托管数据库

环境变量变化不会重建已有角色。旧卷超级应用角色或仍拥有 schema 的角色会被启动校验拒绝。不要删除卷，也不要关闭校验来解决。

维护窗口内由 DBA：

1. 备份数据库、legacy 文件、插件批准图/发行包及分开保管的密钥，保留已验证管理恢复通道。
2. 创建独立非超级迁移角色；盘点库/schema/表/函数/序列与角色成员关系。只转移本应用对象所有权，不批量改其他应用或扩展对象。
3. 将 public 和应用对象归迁移角色，业务角色保留所需 DML/序列权限。安装与新卷相同的迁移角色默认授权；历史表仅授 SELECT。
4. 撤销业务角色的集群属性、预定义特权角色成员、迁移身份成员以及 database/schema CREATE。数据库仍由 DBA 管理。
5. 配置独立迁移连接并启动；核查当前身份、所有权、启动迁移、插件生命周期、登录/权限、导入/文件及恢复，再恢复流量。

仓库不自动改变真实生产账号。托管数据库必须使用提供商支持的管理流程。旧库完成与否以实际维护和验证结果为准。

## 验收

`python3 scripts/verify-deployment-role.py` 只在独立 QA 管理员数据库环境执行。它新建唯一库与两个角色，调用真实初始化脚本，以迁移身份执行原始 B52 和全部七份插件初始化，以运行身份验证 CRUD、序列默认授权、临时表及集群/DDL/角色切换拒绝，最后清理自身对象。

Java 测试验证特权成员链和配置缺失拒绝。真实应用启动验证默认插件迁移与历史权限；CI 浏览器在一次性 Compose 中检查真实用户流程。所有检查需以该提交的实际结果为准。完整平台恢复、生产容量和外部服务联调另设准入门槛。
