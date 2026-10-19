package com.lumira.platform.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 返回宿主按可信代理规则解析的地址；插件不能自行信任转发头。 Returns the host-resolved address under trusted-proxy rules;
 * plugins must not trust forwarded headers themselves.
 */
@FunctionalInterface
public interface ClientAddressPort {
    /**
     * 解析请求客户端地址；仅宿主配置的可信代理头可生效。 Resolves the client address using only host-configured trusted
     * proxies.
     */
    String resolve(HttpServletRequest request);
}
