package com.lumira.plugins.eventconsole;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

public final class LumiraPlugin extends PluginEntry {
    public LumiraPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return Configuration.class;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "com.lumira.plugins.eventconsole",
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = {
                                PlatformEventOutboxController.class,
                                PlatformEventOutboxQuery.class,
                                PlatformEventTraceQuery.class,
                                PlatformEventOutboxAdminService.class,
                                PlatformEventReplayApprovalRepository.class
                            }))
    @EnableTransactionManagement(proxyTargetClass = true)
    public static class Configuration {}
}
