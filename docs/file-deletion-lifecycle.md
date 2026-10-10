# 文件删除与配额释放

移入回收站只把文件从 `READY` 改为 `TRASHED`，不删除字节，也不释放文件配额。永久删除先在数据库事务中校验所属账号或管理员全局权限、回收站状态和客户端版本，再将元数据改为 `DELETING` 并递增版本。

该事务提交后，服务针对这个文件调用 BlobStore 删除，并确认所有实体字节和迁移中的本地副本均已清除后，才用 `id + version + DELETING` 条件删除元数据。存储操作在事务和账号/文件行锁之外执行。PostgreSQL 分块保留期间，`DELETING` 元数据、字节额度和文件槽位继续计费；物理清除和元数据扫尾完成后，下一次 `/api/account/files/usage` 才会显示降低后的 `chargedBytes` 和 `fileCount`。

如果存储删除或元数据完成事务失败，接口仍接受这次永久删除请求，元数据继续处于 `DELETING` 并继续占用配额。`pendingDeletingCount` 和 `pendingDeletingBytes` 报告这些仍计费的记录；它们已经包含在 `fileCount` 和 `chargedBytes` 中。后台清理 worker 会在至少一分钟后重试，失败时重新安排下一次尝试。上传过期记录仍保留原有过期宽限和清理流程。

PostgreSQL BlobStore 对已完成对象只设置一次 24 小时下载宽限期；未发布或无元数据的孤立分块立即清除。物理清理每分钟最多执行 16 个独立的 1,000 块批次，并在批次间检查 5 秒总预算；单条 SQL 仍使用数据库自身超时。待宽限期的文件会移动到清理队列末尾，避免它们长期占住每轮前 100 个位置。RoutedBlobStore 还会确认迁移期间的本地副本已删除，才释放配额。其他 BlobStore 必须支持幂等删除；worker 可在存储已经完成而元数据提交未完成时再次删除同一 UUID。

`FileServiceTest` 和 `FileCleanupTest` 覆盖提交后删除、版本 CAS、失败保留和 worker 重试。启用 `LUMIRA_TEST_DB_URL` 时，`FilePurgeQuotaDatabaseTest` 在专属临时 schema 中验证真实 PostgreSQL 配额计数，不访问平台业务表。浏览器显示及刷新后的配额确认仍属于独立的 UI 验收。
