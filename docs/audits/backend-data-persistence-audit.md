# 后端数据存储检查与整改

**日期：** 2026-09-25  
**范围：** 后端数据持久化、文件存储、运行缓存和待确认操作。

## 处理结果

| 项目 | 整改结果 |
| --- | --- |
| 新上传文件正文 | 改为写入 PostgreSQL，按 64 KiB 分块保存；下载时分批读取，并校验文件大小和 SHA-256。 |
| 已有本地文件 | 新增启动迁移：逐个复制到数据库，校验大小和摘要，通过后切换存储位置并清理旧文件。校验失败时保留原文件并让启动报错，不会把未迁移文件误当成已入库。 |
| 待确认的 AI 管理操作 | 改存 PostgreSQL，保留三分钟有效期；确认时按操作者原子消费，不能被另一实例重复确认。没有保存密码或 AI 对话内容。 |
| 敏感词缓存 | 编译后的规则仍只在内存中作为派生缓存；数据库规则执行增删改或 `TRUNCATE` 时，版本号触发各实例重载，不再只刷新当前实例。 |
| 设置、通行密钥和语言包缓存 | 继续作为可重建的短时内存缓存，不额外写一份到数据库。 |
| 运行日志和进程状态 | 继续作为本地诊断信息，不新增业务数据写入；没有把 AI 对话历史改为持久保存。 |

文件正文保存在 `platform_file_blob_chunk` 表中，每个分块最多 64 KiB。上传分批提交，并用文件状态和版本锁防止文件状态变化后继续写入。删除文件时延迟清理数据库分块 24 小时，给已经开始的下载留出完成时间；后台按批清理到期分块。

数据库变更位于 `V6__database_backed_file_and_ai_state.sql`。迁移建立分块表、待确认操作表、敏感词规则版本表和触发器，并为 `platform_file` 增加旧文件清理标记。数据库是唯一持久业务数据存储；编译后的匹配器和短时设置缓存仍可从数据库重建。

## 编译与运行边界

已执行 `./mvnw -q -DskipTests compile` 和 `./mvnw -q -Dmaven.test.skip=true package`，均成功；没有运行测试。V6 已由本地 PostgreSQL 的 Flyway 成功应用，数据库当前版本为 6。启动迁移器时检查到 **0 个待搬迁的已完成本地文件**，所以本次没有旧文件需要复制。

本地后端和前端已启动：`node scripts/status.mjs` 显示 8090 后端和 8010 前端均为 HTTP 200，预览页为 `http://127.0.0.1:8010/user/login`。数据库核对确认 V6 成功、文件分块表和 AI 待确认表已存在，敏感词规则版本触发器已创建。应用启动时若以后发现旧文件，迁移器仍会先校验大小和摘要；校验失败会停止迁移并保留原文件供排查。

## 主要依据

- 数据库连接和迁移配置：[`application.yml`](../../backend/src/main/resources/application.yml#L4)
- PostgreSQL 文件分块结构：[`V6__database_backed_file_and_ai_state.sql`](../../backend/src/main/resources/db/migration/V6__database_backed_file_and_ai_state.sql)
- 上传、下载及完整性校验：[`PostgresBlobStore.java`](../../backend/src/main/java/com/lumira/platform/files/PostgresBlobStore.java)
- 旧文件迁移与校验：[`LegacyFileBlobMigrator.java`](../../backend/src/main/java/com/lumira/platform/files/LegacyFileBlobMigrator.java)
- 待确认操作的数据库原子消费：[`PendingAiActionStore.java`](../../backend/src/main/java/com/lumira/platform/ai/PendingAiActionStore.java)
- 敏感词规则版本检查：[`SensitiveWordRepository.java`](../../backend/src/main/java/com/lumira/platform/settings/SensitiveWordRepository.java)、[`SensitiveWordService.java`](../../backend/src/main/java/com/lumira/platform/settings/SensitiveWordService.java)
