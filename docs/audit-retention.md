# 审计保留

公共审计写入仍与业务事务一起提交，查询按全局权限分页。`AuditRetention` 以 UTC 当前时间减去保留期为截止点，每次只删除一个有界批次；批次通过 `FOR UPDATE SKIP LOCKED` 选择，多个实例同时执行时不会等待整张表，也不会重复删除同一行。

默认配置为保留 365 天、每批 1000 条、每天运行一次且启动后延迟一天：

```yaml
lumira:
  audit:
    retention: 365d
    retention-batch: 1000
    retention-interval-ms: 86400000
    retention-initial-delay-ms: 86400000
```

环境变量 `LUMIRA_AUDIT_RETENTION`、`LUMIRA_AUDIT_RETENTION_BATCH`、`LUMIRA_AUDIT_RETENTION_INTERVAL_MS` 和 `LUMIRA_AUDIT_RETENTION_INITIAL_DELAY_MS` 可覆盖默认值。保留期限制在 7 天至 10 年，批次限制在 1–5000；服务启动时拒绝越界配置。

登录成功、密码错误、限流拒绝和超长密码拒绝在识别到现有账号时写入账号安全记录，来源地址来自受信代理解析，客户端标识经过长度和控制字符清理。未知账号、超长用户名和未列入动作白名单的记录不会落库。生产部署仍需按合规要求确定保留周期、删除审批和备份留存策略；本地默认值不代表合规结论。
