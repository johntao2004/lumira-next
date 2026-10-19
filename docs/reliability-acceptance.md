# 有界可靠性验收 / Bounded reliability acceptance

`scripts/ci-reliability-load.mjs` 仅由当前 GitHub CI 的不可变部署调用。它校验 run/attempt、私有配置权限、精确 Compose 项目、每个容器的项目/服务标签和原始 ID。不得指向生产或复用已有数据。生产 Compose 不为测试改变权限、固定图或网络。

The runner accepts only the current disposable immutable CI project and verifies container identity. It never targets production or existing data and does not weaken deployment restrictions.

## 负载与故障 / Load and faults

- 600 秒观察、最多 20 请求/秒、总请求不超过 12,000；不补发迟到节拍，最多 64 个并发请求，单请求 2 秒截止。
- 两个合成账号，五类等比例只读请求：管理员用户详情、管理员自己、普通用户自己、普通用户被拒的用户列表与模块列表。逐次校验身份、角色及已编辑数据，保留原会话，不重新登录掩盖会话丢失。
- 180 秒仅对本项目 server 强停并启动；360 秒仅暂停本项目 PostgreSQL 10 秒并恢复。每次 60 秒内恢复；所有故障影响单独计数。只有明确故障窗口内 502/503/504 和传输超时可算故障影响，401/409/500、身份或内容错误始终失败。
- 每 30 秒采样 CPU、内存、进程数、网络/磁盘及非故障期数据库连接。空闲内存低于 256 MiB、任一容器进程数达到 512、数据库连接达到上限 80% 时终止并恢复。
- 最少完成 11,760 个节拍请求、正常窗口零错误、正常成功 p95≤1 秒/p99≤2 秒；末尾原会话和真实页面恢复、容器运行且未暂停/未 OOM。任何恢复或清理失败使验收失败。

The 10-minute synthetic read workload is rate/concurrency bounded. Two scoped faults have explicit recovery deadlines, and errors remain separated from successful throughput. Resource safety thresholds stop the test rather than pushing the runner to exhaustion. These are regression gates, not production sizing or SLA commitments.

## 报告与范围 / Evidence and scope

脱敏 `evidence/reliability.json` 包含机器、数据量、请求分布、正常/故障分位耗时、错误、资源曲线及恢复时间。原始 trace、Cookie、配置和服务日志不能上传。负载期间停用浏览器 trace 和 SPA 轮询，避免额外未计数请求及磁盘膨胀。JS finally 与外层 shell 分别恢复暂停数据库并清理本项目。

Only the sanitized report is uploaded. Raw traces, credentials and service logs remain private. Both JavaScript and shell cleanup restore the scoped database and dispose of this project.

这是当前单实例组合的短时故障/持续负载验收，不证明并发写事务持久性、跨节点故障切换、网络分区、多租户、生产容量或超过十分钟的长期浸泡稳定性。同版本固定图的数据库协调测试也不等于已支持跨图滚动升级。真实供应商、生产证书、真实流量及跨地域基础设施必须另行验收。

This verifies the current single-instance composition, not concurrent-write durability, failover, network partitions, multi-tenancy, production capacity or a longer soak. Real suppliers, certificates, workloads and cross-region infrastructure require deployment-specific evidence.
