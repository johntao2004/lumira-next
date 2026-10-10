# 验证器替换、恢复与再次验证契约

本文补充[统一认证设计](role-authentication.md)的 P08 闭环，实施与验收仍需单独记录。现有密码再次验证和首次绑定不等于本文件全部已完成。

## 原子替换

再次验证新增动作 `MFA_REPLACE`。本人 `POST /api/mfa/account/replacement/setup` 输入 `{reauthenticationTicketId, label}`，返回现有 setup 结构 `{challengeId, secret, uri, expiresAt}`；再次验证须包含允许的主方法和当前二次因素。准备的新密钥存于独立短期槽位，原验证器及恢复码继续有效，不能先删除原验证器。

`POST /api/mfa/account/replacement/confirm` 输入 `{challengeId, code}`，确认新验证器后在同一事务替换凭据、废弃全部旧恢复码、生成新的10条恢复码并推进因素凭据修订。本人自愿要求和全局/真实角色要求保持原语义。`POST /api/mfa/account/replacement/cancel` 输入 `{challengeId}`，204；过期或取消只清理准备槽位，不影响原凭据。

setup、confirm 与 cancel 绑定真实账号、当前会话、动作票据、准备时的因素修订及当前政策。新码确认只能成功一次；原凭据期间再次变化则拒绝旧替换。密钥和新恢复码仅在本人受限响应展示，不写入缓存、草稿、日志、审计或自动截图。准备有效期统一为5分钟。

## 管理恢复与受限重新绑定

管理员重置仍使用 `/api/mfa/manage/{userId}/reset` 和 `MFA_ADMIN_RESET` 再次验证票据。必须满足全局 MFA 管理及用户写权限、完整最近主验证和二次因素、必填原因。涉及操作者和目标的账号锁按稳定 UUID 顺序获取，避免两个管理员互相处理时形成循环等待。

重置须保留原 `SELF_ENABLED`，设置 `RECOVERY_PENDING`，推进因素凭据修订并撤销目标会话及旧挑战。只有恢复通知成功加入加密持久队列时才提交重置；无已验证接收渠道、通道未就绪或入队失败时整个事务回滚，原验证器保留。票据不返回管理接口。

恢复票据采用至少256位随机熵，保存用途隔离认证摘要；绑定目标账号、重置后的因素修订和恢复待办，最多15分钟且一次消费。通知正文提供可复制的票据，不把票据放在跳转 URL 或公开结果页。重发废弃之前票据，不清除任何策略要求。

用户完成当前允许的主认证方式后，统一状态为 RECOVERY_REQUIRED。新增 `POST /api/auth/second-factor/recovery/verify` 输入 `{challengeId, recoveryTicket}`，只处理同一当前受限登录尝试；消费票据后返回同一统一 `LoginResponse` 的 ENROLLMENT_REQUIRED。不能建立普通会话。随后沿用受限 enrollment start/confirm，确认新验证器后才清除 RECOVERY_PENDING；本人要求及其他真实来源继续生效。

`POST /api/auth/second-factor/recovery/request` 输入 `{challengeId}`，只在已经存在经授权重置的 RECOVERY_PENDING 时向原已验证渠道重发票据，返回202 `{expiresAt}`。它不能把 MFA_REQUIRED 自动转换为恢复待办，不能接受任意接收地址，不能以邮箱或短信控制权直接删除正常验证器。受限账号、来源及发送次数分别限流。丢失全部因素且未获重置时，页面引导使用恢复码或联系管理员。

恢复码作为一次二次因素仍使用原 second-factor confirm。它不能独立验证主凭据，也不能清除 MFA 要求；成功后提示本人核对验证器和保存新码。

## 非密码再次验证

`POST /api/account/reauthentication/start {action}` 仅返回当前账号实际可用且政策允许的方法，挑战绑定账号、会话、动作、政策与凭据修订。不能对 PASSWORD 被禁止的账号仍只返回 PASSWORD。

EMAIL_OTP/SMS_OTP 使用 `POST /api/account/reauthentication/code` 输入 `{challengeId, method}`，向该账号当前已验证渠道发送独立 REAUTH 用途的六位码，202 `{codeChallengeId, expiresAt, resendAt}`。不接受客户端联系方式。随后 complete 输入 `{challengeId, method, codeChallengeId, code, factorCode?}`。注册、登录、绑定和找回码都不能用于此处。

Passkey 使用 `POST /api/account/reauthentication/passkey/options {challengeId}` 返回 `{publicKey, expiresAt}`，仅允许当前账号凭据；`POST /api/account/reauthentication/passkey/complete` 输入 `{challengeId, credential, factorCode?}`，在签名、RP/origin、challenge、当前凭据和必要二次因素全部通过后返回动作票据。WebAuthn登录证明不能直接变成再次验证票据。

原 `POST /api/account/reauthentication/complete` 的 PASSWORD 输入保持兼容，并复核其当前允许性。所有成功路径返回 `{ticketId, expiresAt, action}`，5分钟、一次消费，绑定账号/会话/动作及最新政策和凭据。失败计数提交，重放、跨动作和跨会话均拒绝；Cookie本身不授权安全凭据变更。微信登录账号如有允许的密码、OTP或Passkey，可以使用这些方式再次验证；如没有任何支持的方法，安全变更明确不可用，不降级免验证。

## 运维恢复与证据

全部管理账号失去验证器时，需要部署操作权的离线恢复命令，明确指定一个账号、原因及不超过15分钟的期限。命令只设置恢复待办并签发一次票据，保留全局/角色/本人要求，撤销旧会话，写独立运维审计；不开放网站运维恢复端点。

当前恢复投递使用目标已验证邮箱的加密邮件队列。没有已验证邮箱时，部署操作者可明确指定权限0600的新临时票据文件进行受控交付；存在已验证邮箱时拒绝文件替代，不输出到标准输出或普通日志。此步骤仍需账号当前允许的主凭据和受限新因子确认，不能直接生成完整登录会话。恢复命令与清理必须在独立 QA 账号上演练后，才可以宣称强制 MFA 的恢复配套就绪。

部署操作者在拥有数据库配置和部署密钥环的受控环境中运行以下命令。`LUMIRA_DB_URL`、`LUMIRA_DB_USER`、`LUMIRA_DB_PASSWORD`、`LUMIRA_SECRET_KEYS` 和 `LUMIRA_SECRET_ACTIVE_KEY` 使用部署已有配置，不能把密钥写入命令行或日志：

```sh
java -jar "$LUMIRA_SERVER_JAR" --offline-mfa-recovery \
  --username '<目标账号>' \
  --operator '<部署操作者>' \
  --reason '<至少8字的恢复原因>'
```

仅当没有已验证邮箱时追加 `--ticket-file '<绝对路径的新文件>'`。已有文件不会覆盖；临时目录须由部署操作者控制。命令在 Spring 启动前退出，不启动网站或后台任务。成功只报告账号、投递方式、期限以及文件路径，邮件 `MAIL` 仍仅表示成功入队。退出码2表示参数错误，3表示密钥不可用，4表示安全失败并回滚，5表示提交结果不确定；出现5时先核对独立运维审计及恢复待办，不盲目重试或清除票据文件。票据完成受控交付后按运维流程清理。

验收分别覆盖：替换准备过期/取消保留旧凭据；新凭据确认使旧码和会话失效；通知入队失败回滚；恢复票据跨账号/修订/用途/会话及并发消费拒绝；受限重新绑定不能访问业务；邮箱、短信和合成 Passkey 再次验证与真实设备验收分开；全部管理员不可用的运维演练。
