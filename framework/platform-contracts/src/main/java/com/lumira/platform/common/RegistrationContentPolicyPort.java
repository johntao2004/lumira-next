package com.lumira.platform.common;

import java.util.UUID;

/**
 * 未登录注册按当前默认注册角色执行内容检查。 Content gate evaluated against the current default registration role
 * without an account.
 */
public interface RegistrationContentPolicyPort {
    /**
     * 按注册角色、文本及来源判断是否应阻止内容。 Reports whether content is blocked for the registration role, text and
     * source.
     */
    boolean blocked(UUID roleId, String text, String source);
}
