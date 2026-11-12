package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Persisted login/registration policy survives removal of its administration plugin. */
@Service
public class MaintenanceReader implements MaintenancePolicyPort {
    public record View(long version, MaintenanceSettings data) {}

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final AdministrationPort guard;

    public MaintenanceReader(
            SettingStore store, ObjectMapper mapper, JdbcClient jdbc, AdministrationPort guard) {
        this.store = store;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public View read() {
        return store.find("maintenance")
                .map(
                        s ->
                                new View(
                                        s.version(),
                                        mapper.readValue(s.payload(), MaintenanceSettings.class)))
                .orElseGet(() -> new View(0, MaintenanceSettings.defaults()));
    }

    @Override
    public boolean canLogin(String username) {
        var setting = read().data();
        if (!setting.activeAt(Instant.now())
                || (guard.hasGlobal(username, PlatformPageId.MAINTENANCE.accessPermission())
                        && guard.hasGlobal(
                                username, PlatformOperation.MAINTENANCE_UPDATE.permission())))
            return true;
        if (setting.allowedRoleIds().isEmpty()) return false;
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_user u JOIN platform_user_role ur ON ur.user_id=u.id JOIN platform_role r ON r.id=ur.role_id WHERE u.username=:name AND u.enabled AND r.enabled AND r.id IN (:roles))")
                .param("name", username)
                .param("roles", setting.allowedRoleIds())
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean publicRegistrationAvailable() {
        return !read().data().activeAt(Instant.now());
    }
}
