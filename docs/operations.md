# 运维手册

本文覆盖企业级审查（2026-09-29）中 P0/P1 项的落地内容：可观测性、HTTP 安全头、备份与恢复、供应链与架构护栏。

## 1. 可观测性

**请求关联。** `RequestCorrelationFilter` 为每个请求生成（或接受调用方提供的）关联 ID：写入 MDC 键 `requestId`，出现在每一行日志的前缀 `[<id>]`，并通过响应头 `X-Request-Id` 返回。调用方提供的 ID 只接受 `[A-Za-z0-9._-]{8,64}`，其余一律替换为新 UUID，防止日志伪造。调度线程和启动日志的 ID 为空。排查流程：用户报告或前端错误带出 `X-Request-Id` → 在日志系统按该 ID 检索整条请求链路。

**指标。** `/actuator/prometheus` 已暴露，所有指标带 `application=lumira-platform` 标签；包含 JVM、Hikari 连接池、HTTP 服务端延迟等 Micrometer 默认指标。

- 该端点在应用内是匿名可读的，**安全依赖网络隔离**：边缘 Caddy 对 `/actuator/*` 一律返回 404，服务端口 8090 只在 compose 内网可达，不映射到宿主机。Prometheus 必须部署在同一内网抓取 `server:8090`。
- 不要把 8090 直接暴露到公网或经其他反向代理转发 `/actuator`。

**建议的最小告警集**（阈值需按容量测试结果校准）：

| 告警 | 指标 | 说明 |
|---|---|---|
| 服务不可用 | `up == 0` 或 `/actuator/health/readiness` 非 200 | 页面级 |
| 连接池耗尽 | `hikaricp_connections_pending > 0` 持续 1 分钟；`hikaricp_connections_active / hikaricp_connections_max > 0.9` | 池大小 12，上传高峰会先在此出现排队 |
| 5xx 比例 | `http_server_requests_seconds_count{status=~"5.."}` 占比 | |
| 队列积压 | 监控页的 Outbox/邮件/短信待发送与失败数 | 已在 `/dashboard/monitoring` 展示 |
| 磁盘/库体积 | `platform_file_blob_chunk` 占库比例 | 触发 D1（对象存储）评估 |

## 2. HTTP 安全头

| 层 | 响应 | 头 |
|---|---|---|
| API（Spring Security） | 所有 `/api/**` 响应，含 401/403 | `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`、`Strict-Transport-Security`（仅 HTTPS 请求）、`X-Content-Type-Options`、`X-Frame-Options: DENY`、`Referrer-Policy`、`Permissions-Policy`、`Cache-Control: no-store` |
| 静态站点（web 镜像内 Caddy） | 前端页面与资源 | 同上，另有面向浏览器页面的 CSP：`script-src 'self'` 加内联引导脚本的 SHA-256 哈希（**不含** `unsafe-inline`/`unsafe-eval`） |
| 边缘（Caddy） | 全部 | `nosniff`、`Referrer-Policy`、`X-Frame-Options`、HSTS |

前端 CSP 由 `scripts/generate-csp.mjs` 在 web 镜像构建时根据实际 `index.html` 生成，所以修改 `.umirc.ts` 里的 `headScripts` 后无需手工更新哈希。`style-src` 保留 `'unsafe-inline'` 是因为 Ant Design 的运行时样式注入；这是已知的、有意的折中。HSTS 只有在 TLS 终结处（外层负载均衡/CDN）生效，边缘 Caddy 本身只监听 HTTP。

## 3. 备份与恢复

数据库是唯一事实源（含文件二进制，见决策 D1），备份即全量数据备份。

**恢复演练（可无人值守运行）：**

```sh
PGHOST=... PGUSER=<有 CREATEDB 权限的角色> PGPASSWORD=... scripts/backup-restore-drill.sh lumira_next /var/backups/lumira
```

脚本对源库做 `pg_dump -Fc`，恢复到一个一次性数据库，逐表精确比对行数，全部一致才返回 0，并始终清理临时库；对比表数为 0 会判失败，避免“空比较也通过”。本机演练结果：45 张表逐表一致。

**建议的策略（需运维方按业务确认后落地）：**

- **RPO / RTO**：建议起点 RPO ≤ 15 分钟（需要 WAL 归档/PITR，托管 PostgreSQL 通常自带）、RTO ≤ 1 小时。`pg_dump` 每日全量只能达到 RPO 24 小时，不足以满足上述目标。
- 备份至少保留 30 天，且**异地/异账号**存放一份；备份文件含全部业务数据与加密后的密钥材料，权限须为 0600，传输和静态存储均需加密。
- **`LUMIRA_SECRET_KEYS` 必须与数据库备份分开保管。** 库里的 SMTP/短信/AI 凭据是用它加密的，缺了它备份里的这些凭据无法解密；反之两者放在一起会使加密失去意义。
- 每季度至少执行一次演练，并把脚本输出留档；每次大版本升级前也应演练。

## 4. 供应链与 CI 门禁

- `.github/dependabot.yml`：Maven、npm、GitHub Actions、Docker 基础镜像的更新 PR。
- CI `images` 任务：构建两个生产镜像并用 Trivy 扫描 HIGH/CRITICAL（仅报告已有修复版本的漏洞）；`dependencies` 任务扫描 `pom.xml` 与 pnpm 锁文件。发现问题会使 CI 失败，处理方式是升级依赖，或在评审中明确记录豁免理由。
- CI `web` 任务现在也执行 `pnpm build`，保证生产构建持续可用。

## 5. 架构护栏

`ArchitectureTest`（ArchUnit）冻结模块依赖图：

- 每个功能模块只能依赖白名单里声明的邻居；新增跨模块依赖必须同时修改 `ArchitectureTest.ALLOWED`，即在评审中显式决策。
- 共享内核 `common`、`modules` 不能反向依赖任何功能模块。
- 模块之间不得出现循环依赖。
- 新增顶层模块包而未登记时测试失败。

当前依赖图无环；`identity`（被 4 个模块依赖）和 `modules`（内核）是变更影响面最大的两个点，改动时需要更谨慎的评审。

## 6. 仍未完成（不属于本次改动）

以下需要基础设施或产品决策，代码无法单方面完成：数据库主从/自动故障切换、多副本滚动升级演练、WAL 归档与 PITR、集中式日志与追踪后端（OpenTelemetry Collector）、CAPTCHA/MFA（见 D3）、AI 外发数据的合规评估（见 D4）。
