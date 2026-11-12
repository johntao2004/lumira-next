package com.lumira.platform.common;

/** Shared owned provider contract for directory business calls across plugin boundaries. */
public interface DirectoryRoleAssignmentServicePort
        extends PluginServicePort, RoleAssignmentPreviewPort {
    @Override
    default String ownerPluginId() {
        return "directory";
    }

    @Override
    default String serviceType() {
        return "role-assignment";
    }
}
