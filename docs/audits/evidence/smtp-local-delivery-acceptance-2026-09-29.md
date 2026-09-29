# SMTP 本机投递验收（2026-09-29）

## 范围

通过已登录的 8020 QA 页面保存临时 SMTP 参数并触发“发送本机测试邮件”。接收端仅绑定 `127.0.0.1:29447`，收件地址固定为 `smtp-test@example.invalid`，邮件正文不落盘。未使用 SMTP 密码、外部收件人或邮件服务。

## 发现与修复

1. `POST /api/platform/settings/smtp/test` 未配置精确安全规则，落入默认拒绝规则，页面提示“无权访问或安全令牌已过期”。在 `SecurityConfiguration` 增加该路径的 POST matcher，要求既有权限 `platform:settings:write`；短信测试路由使用同样的既有权限。
2. 修复路由后，接收端已接受邮件，但服务因审计表 `platform_audit.target_id` 为 `NOT NULL`，而测试投递传入空 `target_id`，导致 UI 报错且未生成审计记录。`MailDeliveryTestService` 与 `SmsDeliveryTestService` 现为本机投递审计生成 UUID；服务测试断言目标 ID 非空。

## 页面与接收端结果

- 接收端相对空基线共收到 2 封测试邮件。第一封是在修复审计目标 ID 前送达接收端，页面仍报告失败且没有对应成功审计；第二封在两项修复后由页面显示成功。
- 第二封页面结果：`本机 SMTP 接收端已接受模拟邮件；这只确认服务器接收，不代表邮件已送达收件箱。`
- 第二封接收记录：`2026-09-29T10:58:06.917Z`（UTC）；页面本地时间 `2026-09-29 18:58:06`；收件人为 `smtp-test@example.invalid`；主题 `Lumira local SMTP test`；`bodyStored=false`。
- 审计页实际显示 `邮件投递 · mail.localTest.ACCEPTED`，目标 UUID `5186d0a7-2572-4101-b096-454a054669be`。
- 原 SMTP 配置已通过 8020 页面保存并再次确认重载：关闭；host/from/fromName/username 均为空；端口 587；STARTTLS；身份认证开启；密码未配置并保留现有密码策略。页面先显示“配置已保存”，随后确认“重新载入配置”对话框。
- 临时 SMTP sink 已停止；`lsof -nP -iTCP:29447 -sTCP:LISTEN` 无监听结果。`node scripts/status.mjs` 显示 frontend PID `76301` 与 backend PID `49218` 均 HTTP 200；8010 前端未重启。

## 自动化检查

最终 focused clean package 成功，包含 37 个测试：37 个无失败/错误，2 个数据库依赖用例跳过。覆盖本机投递服务、投递测试控制器安全规则、SMTP TLS、短信网关、AI 能力门禁、模型连接及本地化服务/恢复。

## 原始证据位置

- 本机接收端两条轻量收件记录（不含正文）：`/tmp/lumira-smtp-qa-20260929/receipt.jsonl`
- 实际应用审计：管理端 `/dashboard/audit`，事件 `mail.localTest.ACCEPTED`，目标 UUID 见上。
- 页面截图曾由 CUA 显示在会话工具结果中，但该界面未提供将截图保存为项目文件的能力；本文件不冒称存在独立 PNG 截图。

## 边界

这证明本机测试邮件被接收端接受、页面返回成功且成功审计记录存在；不证明外部 SMTP 可达、邮件进入用户邮箱、生产邮件队列可投递或短信通道可用。
