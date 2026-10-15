# 平台事件 Outbox

宿主初始化基线 `B52__platform_initialization.sql` 提供 `platform_event_outbox` 的最终表结构、约束和索引。生产模块通过 `DurableEventPublisherPort` 在业务本地事务中写事件；平台适配器把事件追加到 Outbox，业务写入和事件记录同时提交或同时回滚。相同 `sourceType + eventType + eventKey` 或相同事件 UUID 的重复写入保持首次内容，不会覆盖原事件。

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

## 事件操作追踪 API

`GET /api/platform/event-outbox/{eventId}/trace?page=1&pageSize=20` 按事件 UUID 查询安全元数据和消费历史，需要全局 `platform:event-outbox:access`。响应使用 `EventTrace(event, operation, historyStatus, history)`：`event` 含 `id`、来源/类型、状态、attempt、generation、错误代码和时间，以及最近一次成功消费的 `result`；`operation` 是可空的 `OperationMetadata`，给出精确业务类型/ID/版本、审计操作 ID 和 operation 级 `result`；`history` 是 `PageResult<HistoryEntry>`。历史按全局递增 `sequence` 升序分页，包含领取、消费成功、失败/下次重试、无消费者、租约恢复和人工重放审批。错误只返回 `CONSUMER_FAILED`、`NO_CONSUMER`、`LEASE_EXPIRED`、`UNSPECIFIED` 或 `OTHER_ERROR`，不返回异常正文。

`GET /api/platform/event-outbox/operations/{auditId}/trace?page=1&pageSize=20` 先用精确 `platform_audit.id` 关联业务操作，再分页返回 `OperationTrace(operation, items, total, page, pageSize, historyStatus)`。此审计入口同时要求全局 `platform:event-outbox:access` 和 `platform:audit:access`；路由授权和查询层都会检查两项。操作摘要包含 `operationId`、`operationType`、`businessType`、`businessId`、`businessVersion`、`auditOperationId`、`outcomeCode`、预期/已完成事件数、`historyStatus` 和完整操作级 `result`。每个 `items[]` 是 `{event: EventMetadata}`。事件历史分页为 1-based，`pageSize` 上限 100；操作分片按创建时间及事件 UUID 升序。

通知广播以 `broadcastId` 同时作为 `operationId` 和业务 ID；`Published` 保留 `broadcastId`、`recipientCount`，并返回 `operationId`、精确 `auditOperationId` 和本次 `eventIds`，前端可直接打开该操作。工单创建/处理分别用 `feedback.create` / `feedback.handle` 操作类型、`FEEDBACK` 业务类型、工单 ID 和对应版本；事件发布、业务写入及同一审计记录 ID 在一个业务事务内提交。工单插件 1.0.14 起传递精确操作与审计 ID；旧插件仍可通过 SDK 默认兼容重载工作，但无法提供精确操作关联，需更新插件包才能完整追踪。

消费者在副作用与事件成功确认的同一事务中保存收件箱结果快照；失败尝试回滚后，worker 再用有效租约令牌写入失败和下一重试时间。operation 状态优先反映关联分片中最严重的状态：`DEAD_LETTER`、`NO_CONSUMER`、`RETRY_PENDING`、`EVENTS_PENDING`，全部完成后才以计数结果归为 `DELIVERED`、`PARTIAL`、`ALL_SKIPPED`、`EXPIRED_ALL` 或 `DUPLICATE_ONLY`。消费者 skip reason 目前包括 `EXPIRED`、`RECIPIENT_INACTIVE`、`RECIPIENT_DELETED`、`DUPLICATE`、`GLOBAL_FEEDBACK_MANAGER`。因此 `DELIVERED` 表示事件消费事务成功，不表示所有通知都写入收件箱。

无事件的工单操作仍持久化 `NO_EVENTS` 操作记录和明确结果，例如 `SUPPRESSED_GLOBAL_MANAGER` 或 `NO_RECIPIENT`，不会表现为关联丢失。`AVAILABLE` 表示从第一条领取记录起的追踪可用；旧事件已有尝试次数或从人工重放记录开始的历史标记为 `PARTIAL`；从未领取且尚无任何历史的旧事件为 `NOT_RECORDED`；事件保留期已清理后标记 `EXPIRED`，部分分片过期标记 `PARTIAL`。`EXPIRED` / `PARTIAL` 明确说明历史不完整，不能用作完整过程证据。事件保留清理级联删除其追踪行并累计 operation 的过期分片数；operation 摘要保留至对应审计行过期，孤立摘要由定期清理回收。带人工重放审批引用的事件继续保留，确保审批记录可追溯。

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
