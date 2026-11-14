# 运维手册

本文覆盖企业级审查（2026-09-29）中 P0/P1 项的落地内容：可观测性、HTTP 安全头、备份与恢复、供应链与架构护栏。

## 1. 可观测性

**请求关联。** `RequestCorrelationFilter` 为每个请求生成（或接受调用方提供的）关联 ID：写入 MDC 键 `requestId`，出现在每一行日志的前缀 `[<id>]`，并通过响应头 `X-Request-Id` 返回。调用方提供的 ID 只接受 `[A-Za-z0-9._-]{8,64}`，其余一律替换为新 UUID，防止日志伪造。调度线程和启动日志的 ID 为空。排查流程：用户报告或前端错误带出 `X-Request-Id` → 在日志系统按该 ID 检索整条请求链路。

**指标。** `/actuator/prometheus` 已暴露，所有指标带 `application=lumira-platform` 标签；包含 JVM、Hikari 连接池、HTTP 服务端延迟等 Micrometer 默认指标。

- 端点**默认不存在**：未设置 `LUMIRA_METRICS_TOKEN`（至少 32 字符）时返回 404。设置后，抓取方必须带 `Authorization: Bearer <token>`（`MetricsTokenFilter`，常量时间比较；缺失或错误返回 401）。Prometheus 示例：`authorization: { type: Bearer, credentials_file: /etc/prometheus/lumira.token }`。
- 纵深防御：边缘 Caddy 对 `/actuator/*` 一律返回 404，服务端口 8090 只在 compose 内网可达、不映射到宿主机；Prometheus 部署在同一内网抓取 `server:8090`。不要把 8090 直接暴露到公网，也不要经其他反向代理转发 `/actuator`。
- **访问日志**：每个请求在 `lumira.access` 记录器输出一行 `METHOD path -> status in N ms`，带 requestId 前缀；不记录查询串（可能含令牌）。`/actuator/*` 探针降到 DEBUG，避免健康检查刷屏。

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

当前新文件二进制存于 PostgreSQL，但未迁完的 legacy 本地文件仍可能存在。完整恢复需要数据库、legacy 文件卷、不可变发行包和插件批准图，并单独保管解密密钥；仅恢复数据库不保证完整服务。

**恢复演练（可无人值守运行）：**

```sh
PGHOST=... PGUSER=<有 CREATEDB 权限的角色> PGPASSWORD=... scripts/backup-restore-drill.sh lumira_next /var/backups/lumira
```

脚本用同一导出快照执行 `pg_dump -Fc` 和源数据校验，恢复到一次性数据库，逐表比较行数及有序行内容的 SHA-256 摘要；空表清单、任一查询/导出/恢复失败或摘要不一致均返回非零。客户端流式计算摘要，数据库排序可能使用临时磁盘。检查结束清理临时库，成功时保留备份文件；只接受不带路径或 SQL 标点的简单数据库名。需要 Python 3 与匹配版本的 PostgreSQL 客户端，单条数据库语句最长10分钟、连接10秒，客户端每阶段默认15分钟（LUMIRA_BACKUP_TIMEOUT_SECONDS 可设为1–3600秒）；大库应在评估后采用合适的备份体系，而非删掉超时。该演练不包含对象所有权/授权恢复、密钥、legacy 文件、登录或业务链路验收，不能替代完整平台恢复。

**建议的策略（需运维方按业务确认后落地）：**

- **RPO / RTO**：建议起点 RPO ≤ 15 分钟（需要 WAL 归档/PITR，托管 PostgreSQL 通常自带）、RTO ≤ 1 小时。`pg_dump` 每日全量只能达到 RPO 24 小时，不足以满足上述目标。
- 备份至少保留 30 天，且**异地/异账号**存放一份；备份文件含全部业务数据与加密后的密钥材料，权限须为 0600，传输和静态存储均需加密。
- **`LUMIRA_SECRET_KEYS` 必须与数据库备份分开保管。** 库里的 SMTP/短信/AI 凭据是用它加密的，缺了它备份里的这些凭据无法解密；反之两者放在一起会使加密失去意义。
- 每季度至少执行一次演练，并把脚本输出留档；每次大版本升级前也应演练。

## 4. 供应链与 CI 门禁

- `.github/dependabot.yml`：Maven、npm、GitHub Actions、Docker 基础镜像的更新 PR。
- CI `images` 任务：构建生产镜像（server、web、office-worker、edge，以当前 workflow 为准）并用 Trivy 扫描 HIGH/CRITICAL（仅报告已有修复版本的漏洞）；另对标准部署的派生 `lumira-database:18.6` 执行相同镜像门禁并记录解析摘要；`dependencies` 任务扫描 `pom.xml` 与 pnpm 锁文件。发现问题会使 CI 失败，处理方式是升级依赖，或在评审中明确记录豁免理由。
- CI `web` 任务现在也执行 `pnpm build`，保证生产构建持续可用。

## 5. 架构护栏

`ArchitectureTest`（ArchUnit）冻结模块依赖图：

- 每个功能模块只能依赖白名单里声明的邻居；新增跨模块依赖必须同时修改 `ArchitectureTest.ALLOWED`，即在评审中显式决策。
- 共享内核 `common`、`modules` 不能反向依赖任何功能模块。
- 模块之间不得出现循环依赖。
- 新增顶层模块包而未登记时测试失败。

当前依赖图无环；`identity`（被 4 个模块依赖）和 `modules`（内核）是变更影响面最大的两个点，改动时需要更谨慎的评审。

## 6. 仍未完成（不属于本次改动）

以下需要基础设施或产品决策，代码无法单方面完成：数据库主从/自动故障切换、多副本滚动升级演练、WAL 归档与 PITR、集中式日志与追踪后端（OpenTelemetry Collector）、已实现 CAPTCHA/MFA 的实际供应商与完整界面联调、AI 外发数据的合规评估（见 D4）。

## 7. 云端模型服务与代理 DNS

云端模型服务仍要求 HTTPS 公网目标；Ollama/LM Studio 使用各自的本机接口。正常公网 DNS 解析直接使用系统结果。若本机代理返回 198.18/15 虚拟地址，云端连接会通过固定的 Cloudflare 1.1.1.1 HTTPS DNS 查询真实公网结果，再固定连接到经过验证的地址，同时保留原服务域名的 TLS 校验。混合私网、内网或其它保留地址仍会拒绝，不会直接连接到代理虚拟地址。

该回退只向 Cloudflare 发送服务域名的 DNS 查询，不发送模型服务密钥。A/AAAA 并行查询有 3.7 秒总预算，结果最多缓存 60 秒、128 项；禁止重定向且响应大小有界。受限网络若需要此回退，应允许到 1.1.1.1:443 的出站连接；本流程不修改操作系统 DNS 或代理配置。

已保存的模型密钥仅在同一服务类型和规范化地址下复用。修改连接信息必须替换或清除旧密钥；需要密钥的云端服务仍要求提供有效密钥。模型列表需在实际配置的服务上验证，临时验收输出保存在 `.runtime`。

## 数据库发行镜像 / Database distribution image

部署使用 `deploy/database.Dockerfile` 构建的 `lumira-database:18.6`，保留官方 PostgreSQL 18.6 及其入口/数据目录/停机信号。基底固定 digest `sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336`；仅将官方 gosu 1.19 commit `6456aaa0f3c854d199d0f037f068eb97515b7513` 用 Go 1.26.9 静态重编译，保留 go.sum 和构建元数据。CI 扫描真正部署的派生镜像，且以同镜像运行真实浏览器/三角色初始化和故障恢复。原始 PostgreSQL 镜像仍作为隔离后端测试的同版本引擎和命令行客户端，不是本项目生产发行镜像。

CI79 对上游 gosu 旧 Go 标准库命中25项有修复的HIGH/CRITICAL及一个默认测试私钥；这是组件扫描发现，并非25条已实证可利用路径。没有以可达性分析豁免门禁。官方源码与说明：[gosu 1.19](https://github.com/tianon/gosu/tree/6456aaa0f3c854d199d0f037f068eb97515b7513)、[上游安全范围](https://github.com/tianon/gosu/blob/6456aaa0f3c854d199d0f037f068eb97515b7513/SECURITY.md)。最终是否通过以对应提交扫描结果为准。

派生层删除 `/etc/ssl/private/ssl-cert-snakeoil.key` 和 `/etc/ssl/certs/ssl-cert-snakeoil.pem` 的默认测试材料。它不启用真实 TLS，不关闭部署已有 TLS；既有配置若引用这些测试路径，必须先配置受控真实证书并现场验收，不能依赖测试证书继续运行。历史基底层的公开测试材料不因此被擦除。参见 [PostgreSQL 18 SSL配置](https://www.postgresql.org/docs/18/runtime-config-connection.html#RUNTIME-CONFIG-CONNECTION-SSL)。

The derived image keeps the upstream engine and entrypoint. It replaces only the pinned gosu helper, removes bundled test certificates, and retains high/critical vulnerability and secret gates. Actual TLS, existing-volume configuration and certificate custody still require deployment-site validation.
