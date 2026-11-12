# Kubernetes 部署与容量边界

`deploy/kubernetes` 提供内部服务部署基线，包括后端、静态前端、隔离的 Office worker、后端 HPA 和中断预算。隔离实验环境中的部署验证不能替代生产容量验收，也不代表 1000 活跃用户或 10,000 成功请求/秒已达标；容器基线默认采用不可变固定插件图（`LUMIRA_PLUGINS_FIXED_GRAPH=true`）。

## 环境配置

使用环境独立的 Kustomize overlay 引用该目录，将三个镜像名替换为由本次源码构建的不可变 digest；分别使用 `deploy/server.Dockerfile`、`deploy/web.Dockerfile`、`deploy/office-worker.Dockerfile`。`replace-me` 故意不能作为发布版本。先执行 `kubectl kustomize <overlay>`，再在隔离集群执行 `kubectl apply --dry-run=server -k <overlay>`。正式应用由环境发布流程负责。

在目标 namespace 通过密钥管理器提供 `lumira-runtime` Secret。后端所需键为 `LUMIRA_DB_URL`（外部 PostgreSQL 18 JDBC 地址）、`LUMIRA_DB_USER`、`LUMIRA_DB_PASSWORD`、`LUMIRA_BOOTSTRAP_PASSWORD`、`LUMIRA_SECRET_KEYS`、`LUMIRA_SECRET_ACTIVE_KEY`、`LUMIRA_OFFICE_WORKER_TOKEN`；可选 `LUMIRA_METRICS_TOKEN`。具体格式沿用本地配置契约，必须使用该环境新生成的值，不能复制本地业务凭据。Office worker 只收到自身 token。Secret 不应包含与容量 ConfigMap 重名的覆盖键。

不在此基线里创建数据库或数据库磁盘。数据库由托管 PostgreSQL 或已有数据库 operator 管理，其连接地址、TLS、存储、备份和故障切换按环境配置。数据库扩容不能由应用 HPA 代替，也不能在未验证连接预算时盲目放大连接池。

所有 Service 都是 ClusterIP。环境入口需将 `/api` 路由至 `lumira-server:8090`，其余路由至 `lumira-web:8010`，终止 TLS，并禁止公开代理 `/actuator`。当前客户端地址契约只信任显式配置的直接代理 IP：代理必须覆盖 `X-Lumira-Client-IP`，后端设置 `LUMIRA_TRUSTED_PROXY_ADDRESSES`。不要把整个 Pod 网段当作可信客户端；如入口 IP 会变化，应先完成与该入口控制器相适配的身份传递方案。未解决此项不能进行共享来源限流的容量验收。

Office worker 依赖支持 NetworkPolicy 的 CNI；策略只允许后端访问 18091，并禁止 worker 出网。部署前确认策略实际生效。worker 副本数和资源单独调整，转换忙碌响应不计入成功吞吐。

## Office worker 运行时约束

worker 保留启动时的硬约束校验：1 GiB 内存、零 swap、最多 1 CPU，以及 16–128 个进程/线程名额。仅设置 Pod CPU/内存限制还不够；Kubernetes 的通用 Pod 资源字段不提供独立的容器 PID 上限，参见 [Kubernetes PID 管理](https://kubernetes.io/docs/concepts/policy/pid-limiting/)。不要通过关闭校验或放宽上限让容器启动。

部署使用 `lumira-office` RuntimeClass，调度到标记 `lumira.io/office-runtime=true` 的节点。节点必须先配置同名容器运行时 handler，再验证实际 cgroup 后添加标签。`deploy/kubernetes/containerd-office-runtime.toml` 是 containerd 2.x 的主机配置片段，不是 kubectl 资源；它使用与普通工作负载相同的 runc，并单独约束 worker 进程数。

节点准备时，使用该节点版本的 `ctr oci spec` 生成默认 OCI JSON，保留其余默认字段，只设置 `linux.resources.pids.limit=128`，保存到 `/etc/lumira-office/runtime-spec.json`。将 TOML 片段纳入该节点的 containerd 配置，按节点维护流程重载运行时并重新创建 worker Pod。不要复制其他机器完整的 OCI spec，也不要将所有数据库和业务 Pod 一并限制为 128 个进程。containerd 的基础 spec 在守护进程启动时载入，已创建的容器不会自动采用新 spec，见 [containerd CRI 配置](https://github.com/containerd/containerd/blob/main/docs/cri/config.md)。K3s 可按其 [containerd 配置说明](https://docs.k3s.io/advanced#configuring-containerd) 管理模板或受支持的导入配置。

验收需在实际 worker 容器内确认 `pids.max=128`、`memory.max=1073741824`、`memory.swap.max=0`，健康检查与真实 Office 转换均成功，并验证后端能访问 worker、前端不能访问 worker、worker 不能访问数据库。对不允许定制运行时的托管节点，必须先找到具有等价约束的受支持运行时，再替换 RuntimeClass，不能直接移除该要求。

三个应用 Deployment 均关闭 `enableServiceLinks`，服务发现使用 DNS 和显式配置。否则同名 Service 自动注入的 `LUMIRA_OFFICE_WORKER_PORT=tcp://…` 会与 worker 的数字端口配置冲突。

## 弹性和连接预算

初始配置后端 2–8 副本，每副本请求 1 CPU/1 GiB，限制 2 CPU/2 GiB。HPA 同时观察 CPU 与内存利用率，缩容稳定窗口 300 秒。必须先安装并验证 metrics API。HPA 调整副本数，不会自动修改 Pod 的 CPU/内存请求，也不会自动创建云节点或扩容数据库；节点伸缩需环境自己的 autoscaler，垂直资源变更需单独 overlay 和压测，不与基于相同指标的 VPA 自动修改混用。机制见 [Kubernetes HPA 官方说明](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/)。

默认每副本 Hikari 最大 12，滚动发布允许 1 个额外副本。因此单次滚动至少为 `(8 + 1) × 12 = 108` 个应用连接预留预算，再加数据库管理、迁移、监控及其他服务连接。多次滚动中尚未退出的 Pod 也占连接，不能只按 HPA 的 8 个副本估算。入口每副本最多接收 256 个在途 API 请求；超过上限返回 `503/serverBusy` 和 `Retry-After`。拒绝属于过载失败，不能计入 10,000 成功 RPS。

`LUMIRA_SESSION_TOUCH_WINDOW_MS` 仅支持 0–1000 毫秒；应用默认 0，保持逐请求写入会话访问时间，Kubernetes 容量模板显式设置 1000。启用后只合并窗口内未改变主体、会话 ID 和超时配置的访问时间更新；会话属性仍立即保存，注销仍直接删除共享会话。访问时间最多落后一个窗口，因此闲置超时可能提前不超过一个窗口；短超时会话与接近到期的访问仍立即持久化。多个节点使用数据库中的同一行判断窗口，不使用节点本地会话缓存。该值与隔离容量测试一致，不代表生产容量已经达标；上线前须在目标环境验证撤销、会话轮换、超时及容量。

JVM 使用容器内存上限；调整限制时必须为 native memory、线程、类元数据、临时文件和 GC 留余量。JVM 堆保留可能阻碍内存 HPA 缩容，需用持续负载和空闲恢复确认实际曲线。没有目标环境证据时，不建议只调高线程数、连接池或副本上限。

## 插件与数据

容器默认使用固定插件图，制品打包在镜像中，每个 Pod 独立解压至 `/tmp`。可选将 `LUMIRA_PLUGINS_ARTIFACTS_PATH` 指向共同挂载的不可变制品目录，挂载配置及存储验证由环境 overlay 提供；`LUMIRA_PLUGINS_PATH` 和其中的 `loaded/` 仍由每个 Pod 独占。共享制品的配置与保数据切换要求见[插件运行说明](plugins.md)。相同插件图可以增加副本；**改变插件图的版本目前不能直接按普通滚动发布处理**。运行时仍会拒绝不同图与现有活跃节点混用。固定插件图（`LUMIRA_PLUGINS_FIXED_GRAPH=true`）制品打包在镜像中，变更镜像图时必须排空旧节点，不能在普通 RollingUpdate 中混用两种不同固定图；非固定图部署若挂载共享不可变 `LUMIRA_PLUGINS_ARTIFACTS_PATH`，可开启 `LUMIRA_PLUGINS_CLUSTER_ENABLED=true` 并安装启用 `cluster-runtime` `1.3.0`（声明 `protocol_version = 2`），通过受围栏保护的两阶段在线变更与冷替换恢复协议协调多节点（见[插件运行说明](plugins.md)）。不要删除这个拒绝保护来让发布“通过”。

新文件使用共享数据库 BlobStore。现存本地文件必须先通过经过验证的保数据迁移进入共享存储；此模板不挂载或迁移用户的本地上传目录。原有迁移身份和 checksum 必须匹配，不能通过 repair 绕过。

就绪探针包含数据库和插件图状态；存活探针只检查应用存活，避免数据库短断引发所有实例循环重启。关闭时先等待端点摘除，再进行请求和插件排空；120 秒终止宽限期要按最长允许请求、异步任务及插件关闭时间验证。PDB 限制自愿中断，不能替代节点故障、跨区部署和数据库恢复试验。

验收需独立记录成功 RPS、拒绝率、业务错误率、P95/P99、连接等待、CPU/堆/GC、队列最老年龄，以及扩缩容和数据库短断后的恢复。1000 在线用户和 10,000 次请求/秒应分别施加负载；客户端未发出的请求也要计为未达成，不能只统计成功发出的部分。

## 隔离环境混合 CRUD 基线

`scripts/mixed-crud-load.mjs` 使用真实登录、独立会话、CSRF 和授权版本执行字典新增、读取、修改、删除及列表查询。它不是所有业务功能的容量证明。先按 QA fixture guard 准备独立数据库、标记和私有配置，设置 `LUMIRA_SMOKE_CONFIG`、`BASE_URL` 以及 `PSQL_BIN`；入口只接受回环地址，并核验 HTTP 会话实际绑定的数据库。远程 VM 上应在 VM 内运行，不能用业务配置代替。

```sh
# 先验证完整 CRUD 流程，再逐级增加负载。
LUMIRA_MIXED_LOAD=1 LUMIRA_LOAD_USERS=5 LUMIRA_LOAD_RPS=5 LUMIRA_LOAD_SECONDS=5 node scripts/mixed-crud-load.mjs
# 1000 个独立会话，30 秒内每秒计划到达 10000 次请求。
LUMIRA_MIXED_LOAD=1 node scripts/mixed-crud-load.mjs
```

账号准备和登录在测量区间外串行进行，每次完成后等待 600ms，保留现有来源限流和密码哈希并发上限；主机变慢时不会累积并行登录工作。准备失败后清理本次夹具。测量采用固定到达计划，每个用户最多一项在途操作；用户忙碌或生成器延迟超过 100ms 的计划请求计入 `missedArrivals`，不在后面补发。超时写入结果未知时不重试原操作。非预期状态、响应语义错误、漏发或恢复检查失败都会令进程失败；503/429 不计入成功吞吐。输出分别列出各 CRUD 操作、成功 RPS、P95/P99 和失败数，附带实际测量起止时间。漏发细分为用户上一项操作尚未完成的 `missedBusyUsers` 和生成器落后计划超过 100ms 的 `missedGeneratorLag`，两者之和仍为 `missedArrivals`；任何漏发都不能计为目标吞吐达标。需要结合服务器监控判断服务瓶颈或负载生成器不足。

脚本只清理本次随机前缀的夹具、测试账号和会话，保留审计记录。持续测试、多个业务混合、跨节点会话、热点冲突、扩缩容与故障恢复仍需独立验收。禁止仅凭小规模基线或 `passedOfferedLoad` 宣称生产万级容量达标。
