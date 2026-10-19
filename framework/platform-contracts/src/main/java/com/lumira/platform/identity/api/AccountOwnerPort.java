package com.lumira.platform.identity.api;

import java.util.UUID;

/**
 * 查询账户所属者 ID 的最小契约；身份仓储及锁语义保持私有。 Minimal account lookup contract exposed to modules that need an
 * owner id. The identity repository and its locking semantics remain private.
 */
public interface AccountOwnerPort {
    /**
     * 按用户名取得账户 ID；lock 为 true 时要求事务内锁定读取。 Returns the account ID for username; lock=true requests a
     * locked read within a transaction.
     */
    UUID id(String username, boolean lock);
}
