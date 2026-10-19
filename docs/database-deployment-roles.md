# 数据库部署角色与密钥

## 当前约束

新卷部署使用两个独立身份：`lumira_bootstrap` 只供官方 PostgreSQL 初始化，`lumira_next` 供宿主及受信插件。两者必须使用不同密码；bootstrap 密码不注入 server。应用角色没有 SUPERUSER、CREATEDB、CREATEROLE、REPLICATION、BYPASSRLS，也没有集群预定义角色成员身份。

应用仍拥有本应用 `public` schema，保留全局 Flyway、运行时插件初始化及卸载所需 DDL。**这不是迁移/运行账号完全分离，也不构成插件安全沙箱。** 后续分离需要独立迁移连接、表所有权/默认授权及动态插件生命周期的完整回归，不能简单撤去 DDL 让安装失效。

`LUMIRA_DB_REQUIRE_UNPRIVILEGED=true` 在全局迁移前检查直接和间接特权身份；检查失败阻止启动。不得通过关闭该配置绕过生产准入。

## 新卷

设置 `LUMIRA_DB_BOOTSTRAP_PASSWORD` 与 `LUMIRA_DB_PASSWORD` 为独立随机值。Compose 将 `deploy/database/10-application-role.sh` 挂到官方初始化目录，仅空数据卷执行。脚本创建非超级应用角色、撤销 PUBLIC 的 schema CREATE、授予本库连接/临时表能力及应用 schema 所有权。不要把密码或数据库内容提交 Git。

server 显式接收持久化 `LUMIRA_SECRET_KEYS` 及 `LUMIRA_SECRET_ACTIVE_KEY`（默认 primary）。密钥环格式、密钥长度和激活项仍由现有 SecretKeys 校验；无效配置不能靠临时生成替代密钥恢复。轮换时保留解密历史数据所需旧密钥，并与数据库备份分开保管。

## 已有卷与托管 PostgreSQL

修改 Compose 环境不会重建已有数据库角色。旧卷上原应用角色仍可能是超级用户；启动校验会拒绝它，而非静默继续。不要删除卷来解决。

维护窗口内先备份数据库、legacy 文件与独立密钥，保留已验证的管理员恢复通道。由 DBA 创建独立管理身份，盘点库/schema/表/函数/序列所有权、角色继承与扩展依赖；确认应用拥有所需本应用对象后，再撤销原应用的集群属性及特权成员关系。数据库所有者应是管理身份，应用仅保留其 schema 的所有权。托管数据库使用提供商受支持的管理流程。

本仓库不自动更改已有生产账号。迁移后应以应用身份检查 `pg_roles` 属性和 `pg_has_role` 成员关系，启动验证全局迁移、插件安装/卸载、导入、文件、权限与恢复。生产实例未执行这些步骤前，不得宣称完成降权。

## 可重复验收

在只用于 QA、具备创建临时数据库/角色权限的管理员环境，执行 `python3 scripts/verify-deployment-role.py`。脚本创建唯一名称的临时库和角色，调用真实新卷初始化脚本，执行原始 B52 与现有插件初始化，验证集群操作被拒绝，最后删除自己创建的测试对象。CI 使用一次性 PostgreSQL 服务执行该检查；不连接生产。

Java `DatabasePrivilegeConfigurationTest` 验证 schema DDL、数据写读、集群操作拒绝、间接特权成员身份拒绝和撤销后的恢复。与真实浏览器、完整平台备份恢复、生产容量及外部服务联调分别验收。
