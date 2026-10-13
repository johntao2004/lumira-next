package com.lumira.plugins.paymentcore;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.ComponentScan;

public final class PaymentCorePlugin extends PluginEntry {
    public PaymentCorePlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return PaymentCorePluginConfiguration.class;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ComponentScan("com.lumira.plugins.paymentcore")
    public static class PaymentCorePluginConfiguration {}
}
