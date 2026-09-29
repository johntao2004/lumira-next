# Lumira 算法基线

截至 2026-09-16，Lumira 采用“按工作负载选择、用基准和不变量验证”的算法基线。不存在脱离数据规模、硬件和一致性要求的绝对“最新最优算法”；因此安全敏感路径优先使用成熟标准，数据路径优先保证复杂度、可并发性和可观测性。

| 领域 | 当前方案 | 复杂度/保证 |
| --- | --- | --- |
| 密码存储 | Spring Security `DelegatingPasswordEncoder` 的版本化 Argon2id；`m=19456 KiB, t=2, p=1`，16 字节盐、32 字节摘要；Bouncy Castle 1.86 | 新写入使用 `{argon2id@lumira_v1}`；旧 raw/tagged BCrypt 仅兼容读取，成功认证自动重哈希；输入上限 256 UTF-8 字节，避免 BCrypt 72 字节截断 |
| 敏感词 | 不可变 Aho–Corasick 快照，发布时原子替换 | 构建为词条总长度线性；匹配为文本长度加匹配数量，公共接口限制返回数量并脱敏 |
| 模块依赖 | DFS 拓扑排序，启动时拒绝重复、缺失、环依赖和 required→optional | `O(V+E)`；前后端 manifest 与数据库归属均在边界检查中校验 |
| 导航树 | 每个父节点只排序一次，迭代后序构建；写入前对完整 proposed parent map 做一次环检测 | 排序 `O(n log n)`，构建 `O(n)`，避免深树递归栈溢出；写事务使用 PostgreSQL advisory lock 串行化图校验和变更 |
| 字典层级 | ID 哈希索引加 BFS/顺序校验 | `O(n)` 检查重复、断链和循环，不在每个节点重复扫描全表 |
| 队列投递 | PostgreSQL `FOR UPDATE SKIP LOCKED`、租约 token/fencing、幂等状态机、有界批量和退避 | 多实例可并发领取，旧 worker 不能确认新租约；不确定结果不伪装成成功 |
| 分页与搜索 | 现有管理 API 保留 page/offset 契约，查询结果按版本化快照读取 | 兼容既有客户端；数据量增长后以 `EXPLAIN (ANALYZE, BUFFERS)` 决定是否切 keyset/trigram，而不是无证据改协议 |

## 参数和升级规则

- Argon2id 参数是可版本化存储契约，不把默认参数隐式绑定到库版本。部署时应在目标硬件上测量登录 P95，并通过新版本 ID 调参；不能在线悄悄改变旧摘要的解释方式。
- BCrypt 72 字节仍只作为遗留约束：当旧摘要仍为 BCrypt 时，超过 72 字节的候选密码直接拒绝，防止“不同输入被截断后相同”的兼容风险。新 Argon2id 摘要允许完整的 256 字节输入。
- 算法替换必须同时更新 API 输入契约、数据库迁移、前端校验、回归测试和运行说明；只替换一个实现层会造成跨模块行为分叉。

## 验证入口

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./backend/mvnw -q verify
pnpm test
pnpm typecheck
pnpm build
pnpm db:schema-smoke
```
