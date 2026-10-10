package com.lumira.platform.dictionaries;

import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.dictionaries.api.DictionaryItemUsagePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class PluginDictionaryUsageBridge implements DictionaryItemUsagePort {
    private final ObjectProvider<PluginExtensionsPort> plugins;

    public PluginDictionaryUsageBridge(ObjectProvider<PluginExtensionsPort> plugins) {
        this.plugins = plugins;
    }

    @Override
    public boolean isInUse(String code, String value) {
        return plugins
                .getObject()
                .extensions(DictionaryItemUsagePort.class, p -> p.isInUse(code, value))
                .stream()
                .anyMatch(Boolean::booleanValue);
    }
}
