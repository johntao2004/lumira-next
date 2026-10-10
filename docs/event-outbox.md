# 平台事件 Outbox

V39 提供跨模块复用的事务事件边界。生产模块在自己的本地事务中调用 `PlatformEventOutboxStore.append`，业务写入和事件记录同时提交或同时回滚；相同 `sourceType + eventType + eventKey` 或相同事件 UUID 的重复写入保持首次内容，不会覆盖原事件。

事件负载先由 Jackson 解析并重新序列化为 JSON，再以 PostgreSQL `jsonb` 保存，最大 256 KiB；来源、类型和幂等键均有格式及长度上限。运维查询只返回来源、类型、幂等键、状态、尝试次数、错误摘要和时间，不读取负载或租约令牌。

| 状态 | 含义 |
| --- | --- |
| PENDING | 等待消费者领取；连接失败可按退避时间再次领取 |
| PROCESSING | 已由一个实例领取，带两分钟租约和随机令牌 |
| DELIVERED | 消费者已成功处理 |
| FAILED | 当前记录了错误并等待下一次有限重试 |
| DEAD_LETTER | 达到八次尝试或租约恢复上限，需要人工处理 |

领取使用 `FOR UPDATE SKIP LOCKED` 和原子令牌更新，旧令牌不能读取、完成或覆盖新领取。失败等待 60、120、240、480 秒后继续，最多八次；过期的处理租约恢复为可重试或死信。消费者必须在自己的处理事务中完成副作用和 `deliver`，以保留幂等边界。

`GET /api/platform/event-outbox` 需要全局 `platform:audit:read`，支持有界分页、来源/类型/键的字面子串搜索和精确状态筛选；`/dashboard/event-outbox` 复用 `ResourceTable`，十秒刷新并保留临时失败时的旧数据。具备全局 `platform:events:write` 的运维人员可以对 `DEAD_LETTER` 执行人工重放，必须填写原因；状态重置、`platform_event_replay_approval` 审批历史和 `event.replay.approved` 审计在同一事务中完成。当前仍未接入赛事业务消费者或外部消息总线。

本地 PostgreSQL 回归覆盖规范化 JSON、幂等、边界、旧令牌隔离、并发领取、指数退避、死信、租约恢复和重放状态 fencing；HTTP 和浏览器回归覆盖权限/全局范围、即时撤权、分页、筛选、搜索、失败刷新、人工重放审批和无横向溢出。生产消息总线、赛事消费者、保留清理和跨节点容量演练仍属于后续交付边界。

已投递事件按配置保留期分批清理，每批有上限。仍被重放审批引用的事件及其 payload 与审批人、原因记录一并保留，避免外键引用让整批清理回滚，也保留人工操作证据。它们会持续占用存储；只有后续明确归档方案和保留期限后，才可一并归档或删除。
