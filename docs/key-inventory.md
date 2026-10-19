# 密钥库存与轮换前置条件 / Key inventory and rotation prerequisites

运行 `python3 scripts/key-inventory.py`，通过已有 libpq 环境或受控密码文件连接。只读工具不接收命令行连接串、不生成或修改密钥、不删除数据。它在单个 `REPEATABLE READ READ ONLY` 事务中查询固定 public 表列，连接上限 5 秒、单语句 15 秒、锁等待 3 秒、进程总时限 60 秒。查询失败、缺列、权限不足、输出不完整或格式异常均返回非零；只输出聚合计数与固定错误，不输出密文、摘要、记录 ID、连接信息或底层 SQL 错误。

Run with an existing libpq environment or controlled password file. The tool changes no records or keys. A single read-only repeatable snapshot covers the fixed public allowlist, with bounded connection, statement, lock and process time. Incomplete or invalid results fail closed. Only aggregates and fixed errors are printed.

## 范围 / Coverage

- 六处加密字段：平台秘密、邮件载荷、短信载荷、公开认证联系人、MFA 认证器及替换认证器的信封。按 v1 key ID 计数；合法语法不证明载荷可以解密。
- 十二处不可归属密钥的 HMAC/派生引用：MFA 恢复码、账号手机令牌、MFA 恢复票据、公开认证 session/contact/code、注册完成 session/fingerprint、公开认证限流、CAPTCHA challenge/proof session、通用限流桶。
- 未装 MFA 可以缺少其表；保留卸载表仍统计；标记已安装却缺表会失败。第三方插件、归档备份和其他集群不在范围内。

Six encrypted fields are grouped by envelope key ID. Twelve HMAC or derived-reference fields have no attributable key ID. Retained MFA tables remain included; missing tables for an installed MFA plugin fail the inventory. Third-party storage, backups and other clusters need separate review.

## 两个独立阻断 / Two separate blockers

`status=observed` 仅表示固定字段已完成观测。`active_key_change_decision=blocked` 和 `retirement_decision=blocked` 始终保留，即使当前计数为零。

1. 切换 active：部分公开认证/MFA 票据按当前 active 直接计算摘要，保留旧钥也不能保证在途挑战、注册幂等回执、CAPTCHA 绑定及限流连续性。必须先在受控维护流程中停止新请求、等待相应挑战自然到期，并单独处理仍有效的幂等回执及派生限流状态。仅等待一个验证码过期不足以解除阻断。
2. 移除旧钥：旧加密信封必须完成受支持的迁移和恢复核验；无 key-id 的长期恢复码不能按库存归属旧钥，也不能从摘要反推出明文重新加密。需要明确的重新签发/撤销方案、备份保留策略及第三方插件核查。当前工具不会自动执行这些动作或宣布可退钥。

Changing the active key can invalidate active-only HMAC comparisons even while old keys remain. Retiring a key additionally requires encrypted-reference migration, recovery verification, an explicit policy for unattributable long-lived digests, and backup/plugin review. Zero counts do not grant approval. No global automatic rotation or rewrapping is claimed.

## 自动验收 / Automated checks

`node --test tests/key-inventory.test.mjs` 验证子进程失败、超时、脱敏、全字段覆盖与零库存阻断。`LUMIRA_DISPOSABLE_QA=true python3 scripts/verify-key-inventory.py` 只允许显式隔离 QA，创建随机库/无登录读取角色，验证真实 B52+MFA 初始化后只读观测，以及合成 old/new、畸形载荷、缺表/缺列/权限不足。结束删除本次随机资源。该命令已进入后端 CI；不得指向生产或共享业务集群。

The integration check creates and removes disposable resources. It validates real schema compatibility and synthetic error cases, not production key custody or a completed rotation.
