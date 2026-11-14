package com.lumira.plugin.sdk;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * Lumira SDK v1；宿主提供认证、事务基础设施及资源登记，业务仍须声明正确事务边界。 Lumira SDK v1; the host provides authentication,
 * transaction infrastructure and resource registration; business code must still define its
 * transaction boundaries.
 */
public abstract class PluginEntry extends Plugin {
    protected PluginEntry(PluginWrapper wrapper) {
        super(wrapper);
    }

    /**
     * 返回插件 Spring 配置入口类；宿主在受管子上下文中装配。 Returns the plugin Spring configuration class for the
     * host-managed child context.
     */
    public abstract Class<?> configuration();

    /** 发布前检查就绪状态；未就绪必须抛出异常。 Must throw when the plugin is not ready. Called before publication. */
    public void checkHealth() {}
}
