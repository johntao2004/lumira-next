# 生产准入与证据边界

目标是可运维的模块化单体：共享 PostgreSQL、受信插件同进程、Office 独立受限容器、静态前端与宿主服务。逻辑分层图见 [当前图](architecture/current-layers.svg)。代码和构建通过不能单独作为生产准入。

## 发布门槛

| 领域 | 必需证据 | 当前约束 |
|---|---|---|
| 构建与供应链 | 干净 checkout、锁文件、SDK/插件/宿主/前端构建、格式/类型/依赖扫描 | 独立 SDK 仍为候选版本，尚未发布到公共制品仓库 |
| 权限 | 两兄弟组织和子组织，多角色/只读/SELF/ORG/SUBTREE/ALL、越权拒绝、撤权同会话即时生效 | 自动化及真实 HTTP 样本需随版本重跑 |
| 界面 | 登录、首次设置、授权刷新、插件装卸、HMR/导航/响应式真实浏览器操作 | CI 真实浏览器分别验证固定生产图和独立动态 QA；未通过的流程仍阻断准入 |
| Office | 真实隔离 worker 的解析、超时、大小限制、打印/导入链路 | CI 使用真实隔离 worker；本地无 Docker 时不得以宿主解析或 mock 替代 |
| 数据 | 支持版本声明、校验和保护、真实备份恢复、加密密钥及遗留文件可用 | 样例 V1→V2 不证明旧平台生产库可升级 |
| 恢复 | 插件装载失败/卸载清理、Outbox回滚与并发完成记账、服务重启 | 外部副作用不在数据库事务保证内，可靠发送需独立机制、幂等与对账 |
| 运维 | TLS边缘、私网DB/worker、最小权限、密钥配置、就绪/指标告警 | 不擅自关闭安全检查来启动服务 |
| 容量 | 代表数据、硬件、接口组合、并发时长、p95/p99/错误与资源曲线 | 小型云 QA 样本不能外推生产容量或SLA |

## 支持的部署边界

生产组合见 `deploy/compose.yml` 与 `deploy/*` 的实际定义。网关与静态站点共享补丁 Caddy 构建阶段；CI 分别构建并扫描 server、web、office-worker、edge 四种应用镜像，阻断有可用修复的 HIGH/CRITICAL 漏洞，并验证网关配置。该门禁不代表所有严重级别、外部 PostgreSQL 镜像或真实部署实例均已扫描；最终结果以对应提交的实际 CI 为准。部署前逐一核验固定插件批准图及其摘要、密钥配置、数据库权限、Office worker 私网和健康依赖；缺少安全所需配置应失败或关闭对应能力，不能默认放开。当前扩展信任模型允许插件执行代码，不接收未审查的任意第三方插件。

备份需包含 PostgreSQL、尚未迁完的 legacy 文件卷、不可变发行包及批准图；解密密钥单独保管。恢复不仅比较行数，还要验证登录权限、文件读取、插件批准图、审计/事件和必需服务。多副本滚动升级、PITR、跨区域灾备、外部服务真实凭据联调需在实际部署环境另行取得证据。

本文件是准入约束，不是“全部通过”声明。每次交付在 `.runtime` 保存真实命令和结果，区分通过、失败、环境阻断和未测；所有阻断关闭后才能宣称该支持部署组合生产就绪。

数据库默认身份与升级检查见 [部署角色](database-deployment-roles.md)：新卷分别使用 bootstrap、非超级迁移 owner 和仅 DML 业务身份；宿主仍持迁移配置，受信插件不是沙箱。旧卷需 DBA 迁移与实际验证，不自动更改。

默认平台合成数据的停机恢复集成门禁见 [平台恢复验收](platform-recovery-verification.md)。该门禁使用当前发行包和独立 QA 数据库，不能替代实际部署的备份保管、跨集群角色恢复、PITR 或 RPO/RTO 验收。

The default-platform synthetic-data offline recovery gate is documented separately; it does not replace deployment-specific backup custody, cross-cluster role recovery, PITR or RPO/RTO acceptance.
