package com.lumira.platform.common;

/** Shared owned provider contract for directory business calls across plugin boundaries. */
public interface DirectoryTemplateServicePort extends PluginServicePort, TemplateUserDataPort {
    @Override
    default String ownerPluginId() {
        return "directory";
    }

    @Override
    default String serviceType() {
        return "template-user-data";
    }
}
