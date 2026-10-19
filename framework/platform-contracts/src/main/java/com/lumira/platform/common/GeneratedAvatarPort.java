package com.lumira.platform.common;

import java.util.UUID;

/**
 * 为账户生成稳定 PNG 头像，不修改账户存储。 Generates a stable PNG avatar for an account without changing account
 * storage.
 */
public interface GeneratedAvatarPort {
    /**
     * 根据账户标识返回 PNG 字节，不写入账户数据。 Returns PNG bytes derived from the account ID without updating
     * account data.
     */
    byte[] generate(UUID accountId);
}
