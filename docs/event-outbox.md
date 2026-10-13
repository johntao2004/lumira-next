# 平台事件 Outbox

V39 提供跨模块复用的事务事件边界。生产模块通过 `DurableEventPublisherPort` 在业务本地事务中写事件；平台适配器把事件追加到 `platform_event_outbox`，业务写入和事件记录同时提交或同时回滚。相同 `sourceType + eventType + eventKey` 或相同事件 UUID 的重复写入保持首次内容，不会覆盖原事件。

事件负载先由 Jackson 解析并重新序列化为 JSON，再以 PostgreSQL `jsonb` 保存，最大 256 KiB；来源、类型和幂等键均有格式及长度上限。运维查询只返回来源、类型、幂等键、状态、尝试次数、错误摘要和时间，不读取负载或租约令牌。

| 状态 | 含义 |
| --- | --- |
| PENDING | 等待消费者领取；连接失败可按退避时间再次领取 |
| PROCESSING | 已由一个实例领取，带两分钟租约和随机令牌 |
| DELIVERED | 消费者已成功处理 |
| FAILED | 已标记失败，需运维关注；当前 worker 对可重试错误写回 `PENDING`，用尽尝试次数后进入 `DEAD_LETTER` |
| DEAD_LETTER | 达到八次尝试或租约恢复上限，需要人工处理 |

领取使用 `FOR UPDATE SKIP LOCKED` 和原子令牌更新，旧令牌不能读取、完成或覆盖新领取。消费失败后等待时间按 60、120、240 秒递增并封顶 960 秒，最多八次；过期的处理租约恢复为可重试或死信。没有匹配消费者的事件会保留为 `PENDING`，至少等待五分钟后再尝试，不会消耗处理尝试次数，也不会自动确认。消费者通过 `DurableEventConsumerPort` 明确注册，worker 在一个数据库事务中执行消费者数据库副作用并确认事件；消费失败时事务回滚，再单独记录重试状态。消费者应使用事件 UUID 和业务幂等键，保证重投不会重复产生业务副作用。该事务边界只保证同一数据库内的业务写入、收件箱副作用与事件状态；外部网络调用或消息总线副作用不在同一数据库原子保证内，需另行设计可靠投递和幂等处理。

当前已接入的业务事件：

| 来源 / 类型 | 生产事务 | 消费效果 |
| --- | --- | --- |
| `notifications / notification.broadcast.requested` | 管理员发布权限校验后，按最多 500 个收件人切片写事件；每片包含发布时解析出的收件人 UUID 快照及通知字段。收件人上限为 10,000，事件负载仍受 256 KiB 总上限约束。 | 通知消费者写入仍启用且未软删除的收件人收件箱，使用 `broadcast:<broadcastId>:<recipientId>` 去重键。过期事件及已删除或停用账号会被跳过。发布接口的 `recipientCount` 表示已入队收件人数，前端提示已加入发送队列；这不代表已经送达收件箱。 |
| `feedback / feedback.notification.requested` | 工单创建或处理及其通知事件在工单事务内一起提交，事件负载保留收件人、工单标题、动作和处理状态快照。幂等键按工单 ID 和版本生成。 | 反馈通知消费者沿用既有 feedback 去重键写入收件箱，并排除拥有全局反馈读取权限的管理员。 |

`GET /api/platform/event-outbox` 需要全局 `platform:event-outbox:access`，支持有界分页、来源/类型/键的字面子串搜索和精确状态筛选；`/dashboard/event-outbox` 复用 `ResourceTable`，十秒刷新并保留临时失败时的旧数据。具备全局 `platform:event-outbox:replay` 的运维人员可以对 `DEAD_LETTER` 执行人工重放，必须填写原因；状态重置、`platform_event_replay_approval` 审批历史和 `event.replay.approved` 审计在同一事务中完成。

事件流状态由拓扑覆盖和 Outbox 计数共同决定，判定顺序如下：

| 状态 | 判定 |
| --- | --- |
| `UNCONNECTED` | 没有已声明的生产者或消费者类型，存在生产事件类型未匹配消费者，或拓扑快照不可用。 |
| `ATTENTION` | 存在未匹配生产类型的消费者，或 `FAILED`、`DEAD_LETTER`、带错误的待重试事件、超期事件计数大于零。 |
| `PROCESSING` | 存在 `PENDING` / `PROCESSING` 积压；或近五分钟有新建事件但没有近五分钟成功投递。 |
| `HEALTHY` | 拓扑已连接且近五分钟至少有一条事件投递成功，无更高优先级的故障或积压。 |
| `IDLE` | 拓扑已连接、无故障或积压，近五分钟也没有新建或成功投递事件。 |

“近五分钟新建”按 `created_at` 统计，“近五分钟已投递”按 `DELIVERED` 状态和 `delivered_at` 统计；带错误的待重试事件指 `PENDING` 且 `last_error` 非空。超期计数包括创建时间已超过五分钟的 `PENDING` 事件，以及租约为空或已过期的 `PROCESSING` 事件。当前 `2/2` 拓扑覆盖表示广播和工单通知这两种已声明事件类型都找到了消费者；它不代表外部消息总线、邮件、短信或其他网络副作用已接入。

Java/PostgreSQL 验收应覆盖规范化 JSON、幂等和负载边界、旧令牌隔离、并发领取、退避、死信、租约恢复、重放状态 fencing，以及通知/工单业务写入与事件的共同提交或回滚、收件人快照和重复投递。配置独立数据库连接 `LUMIRA_TEST_DB_URL`、`LUMIRA_TEST_DB_USER` 和 `LUMIRA_TEST_DB_PASSWORD` 后运行 `pnpm test:java`，启用数据库回归。

浏览器验收应使用独立、带 QA 标记的本地数据库及显式私有配置，不要使用 `.runtime/local.json`。在服务已启动且 `LUMIRA_SMOKE_CONFIG`、`BASE_URL` 指向该 QA 环境后运行 `node scripts/monitoring-broadcast-acceptance.mjs`。该脚本检查空库 `IDLE`、两类业务事件的 `2/2` 拓扑、真实广播进入 `DELIVERED`、收件箱行关联同一 `event_id`、最终 `HEALTHY`，以及桌面和移动视口无横向溢出；证据写入 `.runtime/event-chain-qa`。外部消息总线、跨网络投递、保留清理和跨节点容量演练仍需独立接入与验收。

已投递事件按配置保留期分批清理，每批有上限。仍被重放审批引用的事件及其 payload 与审批人、原因记录一并保留，避免外键引用让整批清理回滚，也保留人工操作证据。它们会持续占用存储；只有后续明确归档方案和保留期限后，才可一并归档或删除。
