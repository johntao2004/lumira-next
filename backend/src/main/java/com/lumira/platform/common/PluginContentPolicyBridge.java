package com.lumira.platform.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The host retains this stable bridge, never a reference to an unloaded plugin bean. */
@Component
public class PluginContentPolicyBridge implements ContentPolicyPort {
    private final ObjectProvider<PluginExtensionsPort> plugins;

    public PluginContentPolicyBridge(ObjectProvider<PluginExtensionsPort> plugins) {
        this.plugins = plugins;
    }

    @Override
    public ScanResult evaluate(String text) {
        return evaluate(null, text, "plugin");
    }

    @Override
    public ScanResult evaluate(String actor, String text, String source) {
        return new ScanResult(
                plugins
                        .getObject()
                        .extensions(
                                ContentPolicyPort.class,
                                p -> p.evaluate(actor, text, source).blocked())
                        .stream()
                        .anyMatch(Boolean::booleanValue));
    }
}
