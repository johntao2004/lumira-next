package com.lumira.platform.common;

/** Owned generation service consumed through a declared, drainable AI dependency. */
public interface AiTextGenerationServicePort extends PluginServicePort, AiTextGenerationPort {
    @Override
    default String ownerPluginId() {
        return "ai";
    }

    @Override
    default String serviceType() {
        return "text-generation";
    }
}
