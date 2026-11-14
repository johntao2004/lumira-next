package com.lumira.plugin.sdk;

import java.time.Duration;

/**
 * 登记插件自有后台任务及订阅；宿主排空调用并关闭登记资源。 Owned background work and subscriptions; the host drains calls and
 * closes registrations.
 */
public interface PluginResources {
    /**
     * 登记命名周期任务，返回可关闭登记句柄；宿主负责生命周期回收。 Registers a named periodic task and returns a closeable
     * handle; the host manages lifecycle cleanup.
     */
    AutoCloseable schedule(String name, Duration interval, Runnable task);

    /**
     * 登记插件自有可关闭资源并返回原资源；卸载时由宿主关闭。 Registers and returns an owned closeable resource for host
     * cleanup on unload.
     */
    <T extends AutoCloseable> T own(String name, T resource);
}
