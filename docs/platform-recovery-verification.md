# 平台恢复验收 / Platform recovery verification

运行 `LUMIRA_RECOVERY_QA=1 python3 scripts/verify-platform-recovery.py` 前，必须使用独立、可丢弃的 PostgreSQL 18 QA 集群，配置回环 `PGHOST=127.0.0.1`、`PGPORT`、`PGUSER`、`PGPASSWORD`。该管理账号只用于创建随机命名的两座 QA 数据库和迁移/运行角色，不能连接生产。脚本结束逐一清理本次数据库与角色，任何验收或清理失败均失败退出。需要 JDK 25、匹配的 PostgreSQL 客户端、当前宿主发行 JAR 与 `node scripts/build-plugins.mjs --no-host` 生成的插件制品。端口默认 18092，可用 `LUMIRA_RECOVERY_QA_PORT` 指定未占用端口。

Use only a disposable PostgreSQL 18 QA cluster. The explicit opt-in creates and removes two randomly named QA databases and migration/runtime roles. Never point this script at production. Current host JAR, trusted plugin artifacts, JDK 25 and matching PostgreSQL clients are prerequisites. Every verification and cleanup failure fails the run.

## 实际验证 / Verified behavior

- 通过真实 HTTP 完成初始管理员改密、普通账号与 SELF 反馈权限、上传文件和创建反馈。
- 停止源应用并确认其数据库连接已断开后，保留 owner/ACL 导出并恢复到新库；不使用 `--no-owner`。
- 对比全部 public 表内容摘要、序列状态及配置、表/模式 owner 与 ACL、默认 ACL、约束所属表与定义、索引、函数和触发器。
- 使用发行 JAR 内真实 SecretStore 验证原始密钥解密；不同密钥必须产生指定的解密异常，其他进程故障不能冒充正确拒绝。
- 独立复制 legacy 文件与插件制品，核对制品摘要。目标实际启动与再次重启后验证普通账号权限、个人文件隔离、legacy 文件读回并迁入 PostgreSQL、新写入和反馈。
- 在源端捕获 PENDING 反馈事件，恢复后核对 DELIVERED 与一份通知记录，第二次重启不能增加同事件通知。此结论限同库消费者效果。
- 验证恢复后的序列递增、唯一/检查/外键约束、未来迁移默认 DML 授权与运行账号持久对象 DDL/TRUNCATE 拒绝。

The drill exercises real HTTP and the packaged application, owner-preserving database recovery, original-key decryption, legacy and PostgreSQL files, trusted plugin artifacts, ordinary-user permissions, a pending database event and two restarts. It also checks restored schema behavior and runtime SQL privilege boundaries.

## 证据与限制 / Evidence and limits

报告 `.runtime/platform-restore-results.json` 记录源提交、JAR SHA-256、检查项和最终状态，只有验收和清理均完成才写 passed。CI 仅上传该脱敏报告，不上传数据库、密钥、Cookie、应用日志或临时目录。临时资料均为本次合成数据；不要用真实用户资料替换。

The sanitized result is passed only after verification and cleanup. Upload only that report, never databases, keys, cookies or private logs.

这是小数据停机恢复演练，不是生产容量、跨区域灾备、PITR、RPO/RTO 保证，也不证明外部邮件/短信恰好一次。Office 文档转换与浏览器交互由独立 CI 门禁验证。角色保留在同一 QA 集群，真实灾备仍须按运维流程单独安全恢复角色与密钥。PG 18 会展平一个已知纯 AND 约束分组，比较器只归一化该精确子表达式，不忽略其他约束差异。

This small offline drill does not establish production capacity, cross-region recovery, PITR or RPO/RTO commitments. External delivery is not exactly-once. Roles remain in the same QA cluster; real disaster recovery must separately restore roles and keys securely. Office and browser acceptance have separate gates.
