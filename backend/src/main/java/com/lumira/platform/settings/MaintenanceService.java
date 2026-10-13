package com.lumira.platform.settings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MaintenanceService implements MaintenancePolicyPort {
    public record RoleOption(java.util.UUID id, String name, boolean enabled) {}

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public com.lumira.platform.common.PageResult<RoleOption> roles(
            com.lumira.platform.common.PageRequest page) {
        String filter =
                " WHERE position(lower(:search) in lower(name))>0 OR position(lower(:search) in lower(code))>0";
        var rows =
                jdbc.sql(
                                "SELECT id,name,enabled FROM platform_role"
                                        + filter
                                        + " ORDER BY name,id LIMIT :limit OFFSET :offset")
                        .param("search", page.search())
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .query(RoleOption.class)
                        .list();
        long count =
                jdbc.sql("SELECT count(*) FROM platform_role" + filter)
                        .param("search", page.search())
                        .query(Long.class)
                        .single();
        return new com.lumira.platform.common.PageResult<>(
                rows, count, page.page(), page.pageSize());
    }

    public record View(long version, MaintenanceSettings data) {}

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final AdministrationPort guard;

    public MaintenanceService(
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

    @Transactional
    public View save(
            MaintenanceSettings data, String actor, long expectedVersion, String changeReason) {
        return save(data, actor, expectedVersion, changeReason, null);
    }

    @Transactional
    public View save(
            MaintenanceSettings data,
            String actor,
            long expectedVersion,
            String changeReason,
            Long sourceVersion) {
        guard.requireOperationGlobal(actor, PlatformOperation.MAINTENANCE_UPDATE);
        if (data == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidMaintenance");
        data.validate();
        if (!data.allowedRoleIds().isEmpty()) {
            int count =
                    jdbc.sql("SELECT count(*) FROM platform_role WHERE enabled AND id IN (:ids)")
                            .param("ids", data.allowedRoleIds())
                            .query(Integer.class)
                            .single();
            if (count != data.allowedRoleIds().size())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidMaintenance");
        }
        var result =
                store.save(
                        "maintenance",
                        mapper.writeValueAsString(data),
                        actor,
                        expectedVersion,
                        changeReason,
                        sourceVersion);
        return new View(result.version(), data);
    }
}
