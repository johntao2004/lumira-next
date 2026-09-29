# 凭据存储规范

当前提供内部 SecretStore、运行时 SecretKeys 和类型化 SMTP 配置接口。SMTP 配置页面已接入 `/dashboard/smtp`；实际邮件发送尚未接入。

- `LUMIRA_SECRET_KEYS` 格式：`primary=<标准 Base64 编码的 32 字节随机密钥>,previous=<另一把密钥>`。最多 16 把；编号使用字母、数字、下划线或连字符。不要使用口令代替随机密钥。
- `LUMIRA_SECRET_ACTIVE_KEY` 指定新写入使用的编号，默认 `primary`。读取可使用密文中引用的保留密钥。
- 未设置密钥环时，其余平台能力仍可启动；加密或读取已有密文返回不可用。非空但格式错误的配置拒绝启动。不会自动生成临时替代密钥。
- 密钥通过部署环境的秘密配置注入，不能写入数据库、前端、普通配置历史或日志。应独立保护并备份密钥；只备份数据库不足以恢复凭据。移除旧密钥前必须完成数据重新加密；当前尚无自动轮换迁移工具。

SecretStore 仅由类型明确的内部服务调用，不作为通用 HTTP 接口暴露。调用者负责权限校验，并在同一个事务内写入业务配置和审计。上下文应包含配置记录的稳定 ID 和字段，例如 `settings:<UUID>:smtp-password`；密文不能跨上下文复制使用。

普通配置 DTO 只返回是否已设置和版本，不能返回密文或明文。`reveal` 返回由调用方持有的字节数组，仅供发送服务短时使用，完成后清除数组。清除凭据可在密钥缺失时执行，但需要调用者授权；数据库保留空值和递增版本，阻止旧请求重新写入。

默认 Maven 测试跳过依赖数据库的四项 SecretStoreIntegrationTest。要完整执行，设置 `LUMIRA_TEST_DB_URL`、`LUMIRA_TEST_DB_USER`、`LUMIRA_TEST_DB_PASSWORD` 后运行 Maven verify。测试在指定数据库内创建随机临时 schema，并在结束后删除，不使用平台业务表。CI 已配置此测试路径，远端执行尚未验证。

## SMTP 配置契约

- `GET/PUT /api/platform/settings/smtp` 读取和保存普通配置；历史与回滚沿用 `/history`、`/history/{version}`、`POST /rollback`。读取要求 settings:read，写入要求全局 settings:write，并执行 CSRF、版本校验与审计。
- 保存结构包含 `version`、`data`、`credentials` 和 `changeReason`。`data` 只包含 enabled、host、port、security、authentication、username、fromAddress、fromName；仅支持 STARTTLS/TLS。
- `credentials.action` 必须显式选择 KEEP、REPLACE 或 CLEAR；仅 REPLACE 接受非空 `value`。界面不能用星号字符串代表保留密码。响应只返回 passwordConfigured、encryptionConfigured，不返回密码或密文。
- 修改主机、端口或认证用户名时，已配置密码不能用 KEEP 复用，必须显式更换或清除。启用认证时必须有可用凭据和密钥。
- 配置、历史、秘密存储和审计处于同一事务。历史从不保存密码；恢复旧配置也必须提交明确的 credentials 操作，无法找回历史密码。

## 本地开发密钥初始化

先启动一次以应用 V23，再执行 `PSQL_BIN=<psql 路径> node scripts/init-secret-key.mjs` 并重启。脚本仅支持本地数据库；已有密钥时保留，缺少密钥但存在密文时拒绝生成替代密钥。新密钥持久化至权限 0600 的 `.runtime/local.json`，不会打印。该脚本不代替生产秘密配置或密钥备份。

`python3 scripts/smtp-smoke.py` 仅在 SMTP 未配置（version 0）时使用独立账号测试，结束后清理。覆盖权限/CSRF、密码三种操作、历史脱敏、目标变更保护、并发冲突、审计故障事务回滚以及配置恢复；不连接 SMTP 或发送邮件。

## 内部邮件传输

`SmtpConnectionSource.load()` 在可重复读事务中取得同一快照下的配置和凭据，返回仅供一次尝试使用的 `SmtpConnection`。消费者必须先结束数据库读取事务，再执行 `SmtpGateway.send()`；不要把网络操作放入外层数据库事务。连接对象持有的明文字节在关闭时清除。Jakarta Mail 的认证接口要求 String，无法保证该库内部字符串即时从内存抹除，因此不缓存发送器、会话或明文配置。

`OutgoingMail` 只支持单收件人、纯文本、受限主题与正文，不接受任意邮件头、附件、抄送或收件人列表。Gateway 使用 Boot 管理的 Jakarta/Angus Mail，显式验证 TLS 主机名，STARTTLS 必须成功；连接超时 5 秒，读写超时 10 秒，禁止部分发送与调试日志。不设置信任所有证书。属性依据 [Angus SMTP 官方文档](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html)。这些是单次 socket 操作超时，不是整个任务的绝对截止时间。

返回结果仅为 ACCEPTED、AUTHENTICATION_FAILED、CONNECTION_FAILED 或 DELIVERY_UNCERTAIN。ACCEPTED 表示 SMTP 服务器接受邮件，不表示进入收件箱；发送阶段发生异常保守归为结果不确定，不能自动盲目重发。QUIT 失败不覆盖已确认的接受结果。当前由内部 MailDeliveryWorker 消费持久化任务并调用传输，尚无公共发送入口或恢复流程；任务生产者仍需负责授权和限流。详见 mail-delivery.md。本地验证未发送外部邮件。

`SmtpTlsTest` 使用 JDK keytool 在临时目录生成仅含 localhost 的两日证书，并在 127.0.0.1 随机端口运行测试服务器。成功路径通过测试专用的证书信任配置使用真实 Angus 传输，验证隐式 TLS 和 STARTTLS、认证、UTF-8 主题与正文、单收件人和点转义；另验证默认信任拒绝自签名证书、主机名不匹配拒绝、认证失败不提交正文以及 DATA 后断开返回结果不确定。测试不会修改 JVM 全局信任库，也不放宽生产证书检查；临时私钥不是部署凭据。
