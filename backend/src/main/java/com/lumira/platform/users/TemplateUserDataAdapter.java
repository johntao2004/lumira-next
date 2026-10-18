package com.lumira.platform.users;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.TemplateUserDataPort;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import com.lumira.platform.identity.api.UserProfileValuesPort;
import com.lumira.platform.organizations.OrganizationService;
import com.lumira.platform.roles.RoleRepository;
import com.lumira.platform.roles.RoleService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Keeps template consumers on a non-secret, independently authorized user-data projection. */
@Component
public class TemplateUserDataAdapter implements TemplateUserDataPort {
    private final AdministrationPort guard;
    private final UserService users;
    private final ProfileSchemaPort profileSchema;
    private final UserProfileValuesPort profileValues;
    private final RoleService roles;
    private final RoleRepository roleRepository;
    private final OrganizationService organizations;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public TemplateUserDataAdapter(
            AdministrationPort guard,
            UserService users,
            ProfileSchemaPort profileSchema,
            UserProfileValuesPort profileValues,
            RoleService roles,
            RoleRepository roleRepository,
            OrganizationService organizations,
            JdbcClient jdbc,
            ObjectMapper mapper) {
        this.guard = guard;
        this.users = users;
        this.profileSchema = profileSchema;
        this.profileValues = profileValues;
        this.roles = roles;
        this.roleRepository = roleRepository;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<Field> readableFields(String actor) {
        if (!hasPage(actor, PlatformPageId.PRINT_TEMPLATES))
            guard.requirePage(actor, PlatformPageId.USERS);
        var fields = new ArrayList<Field>();
        fields.add(new Field("username", "Username", "TEXT", "account", true, false));
        fields.add(new Field("displayName", "Display name", "TEXT", "account", true, false));
        fields.add(new Field("enabled", "Enabled", "BOOLEAN", "account", true, false));
        var schema = profileSchema.read().data();
        schema.forHost("account-profile").fields().stream()
                .filter(
                        field ->
                                !field.custom()
                                        && Set.of("realName", "birthDate", "region")
                                                .contains(field.key()))
                .forEach(
                        field ->
                                fields.add(
                                        new Field(
                                                field.key(),
                                                field.label(),
                                                field.type(),
                                                "profile",
                                                true,
                                                false)));
        schema.forHost("admin-user-editor").fields().stream()
                .filter(field -> field.custom() && !"displayName".equals(field.key()))
                .forEach(
                        field ->
                                fields.add(
                                        new Field(
                                                field.key(),
                                                field.label(),
                                                field.type(),
                                                field.custom() ? "custom" : "profile",
                                                true,
                                                false)));
        if (hasOperation(actor, PlatformOperation.USER_READ_ROLES)) {
            fields.add(new Field("roles", "Roles", "TEXT", "roles", true, false));
        }
        if (hasPage(actor, PlatformPageId.ORGANIZATIONS)) {
            fields.add(
                    new Field(
                            "organizations",
                            "Organizations",
                            "TEXT",
                            "organizations",
                            true,
                            false));
        }
        return List.copyOf(fields);
    }

    @Override
    public List<Field> importableFields(String actor) {
        guard.requirePage(actor, PlatformPageId.USERS);
        boolean canCreate = hasOperation(actor, PlatformOperation.USER_CREATE);
        boolean canUpdate = hasOperation(actor, PlatformOperation.USER_UPDATE);
        boolean canAssignRoles = hasOperation(actor, PlatformOperation.USER_ASSIGN_ROLES);
        boolean canAssignOrganizations =
                hasOperation(actor, PlatformOperation.USER_ASSIGN_ORGANIZATIONS);
        var fields = new ArrayList<Field>();
        fields.add(
                new Field(
                        "username",
                        "Username",
                        "TEXT",
                        "account",
                        true,
                        canCreate || canUpdate || canAssignRoles || canAssignOrganizations));
        fields.add(
                new Field(
                        "displayName",
                        "Display name",
                        "TEXT",
                        "account",
                        true,
                        canCreate || canUpdate));
        fields.add(new Field("enabled", "Enabled", "BOOLEAN", "account", true, canUpdate));
        if (canUpdate) {
            var schema = profileSchema.read().data();
            schema.forHost("account-profile").fields().stream()
                    .filter(
                            field ->
                                    !field.custom()
                                            && Set.of("realName", "birthDate", "region")
                                                    .contains(field.key()))
                    .forEach(
                            field ->
                                    fields.add(
                                            new Field(
                                                    field.key(),
                                                    field.label(),
                                                    field.type(),
                                                    "profile",
                                                    true,
                                                    true)));
            var profile = schema.forHost("admin-user-editor");
            profile.fields().stream()
                    .filter(field -> field.custom() && !"displayName".equals(field.key()))
                    .forEach(
                            field ->
                                    fields.add(
                                            new Field(
                                                    field.key(),
                                                    field.label(),
                                                    field.type(),
                                                    field.custom() ? "custom" : "profile",
                                                    true,
                                                    true)));
        }
        if (canAssignRoles)
            fields.add(new Field("roles", "Role codes", "TEXT", "roles", true, true));
        if (canAssignOrganizations)
            fields.add(
                    new Field(
                            "organizations",
                            "Organization codes",
                            "TEXT",
                            "organizations",
                            true,
                            true));
        return List.copyOf(fields);
    }

    @Override
    public Map<String, String> readValues(UUID userId, String actor) {
        var user = users.find(userId, actor);
        var values = new LinkedHashMap<String, String>();
        values.put("username", user.username());
        values.put("displayName", user.displayName());
        values.put("enabled", Boolean.toString(user.enabled()));
        jdbc.sql(
                        "SELECT real_name,birth_month,region FROM platform_user WHERE id=:id AND deleted_at IS NULL")
                .param("id", userId)
                .query(
                        (rs, n) -> {
                            values.put("realName", rs.getString("real_name"));
                            values.put("birthDate", rs.getString("birth_month"));
                            values.put("region", rs.getString("region"));
                            return 0;
                        })
                .optional();
        var profile = profileValues.readForEditor(userId);
        values.putAll(profile.values());
        if (hasOperationUser(actor, userId, PlatformOperation.USER_READ_ROLES)) {
            var roleIds = roles.userRoles(userId, actor).roleIds();
            values.put(
                    "roles",
                    roleIds.stream()
                            .map(roleRepository::find)
                            .map(role -> role.name())
                            .sorted()
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (hasPage(actor, PlatformPageId.ORGANIZATIONS)) {
            guard.requirePage(actor, PlatformPageId.ORGANIZATIONS);
            guard.requireUser(actor, userId, PlatformPageId.ORGANIZATIONS.accessPermission());
            var organizationIds = organizations.memberships(userId, actor).organizationIds();
            values.put(
                    "organizations",
                    organizationIds.stream()
                            .map(id -> organizations.find(id, actor))
                            .map(organization -> organization.name())
                            .sorted()
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        values.replaceAll((key, value) -> value == null ? "" : value);
        return Map.copyOf(values);
    }

    @Override
    public Map<UUID, Map<String, String>> readValuesBatch(List<UUID> userIds, String actor) {
        return readValuesBatch(
                userIds,
                actor,
                readableFields(actor).stream()
                        .map(Field::key)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Override
    public Map<UUID, Map<String, String>> readValuesBatch(
            List<UUID> userIds, String actor, Set<String> requestedFields) {
        if (userIds == null || userIds.size() > 4_999)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batchLimitExceeded");
        guard.requirePage(actor, PlatformPageId.USERS);
        for (UUID id : userIds)
            guard.requireUser(actor, id, PlatformPageId.USERS.accessPermission());
        if (userIds.isEmpty()) return Map.of();
        boolean includeRoles = false;
        boolean includeOrganizations = hasPage(actor, PlatformPageId.ORGANIZATIONS);
        var profileFields = new java.util.HashSet<String>();
        profileFields.addAll(
                profileSchema.read().data().forHost("admin-user-editor").fields().stream()
                        .map(field -> field.key())
                        .toList());
        profileFields.addAll(
                profileSchema.read().data().forHost("account-profile").fields().stream()
                        .map(field -> field.key())
                        .toList());
        var result = new LinkedHashMap<UUID, Map<String, String>>();
        var rows =
                jdbc.sql(
                                "SELECT id,username,display_name,enabled,real_name,birth_month,region,profile_values::text FROM platform_user WHERE id IN (:ids) AND deleted_at IS NULL")
                        .param("ids", userIds)
                        .query(
                                (rs, n) -> {
                                    var values = new LinkedHashMap<String, String>();
                                    UUID id = rs.getObject("id", UUID.class);
                                    values.put("username", rs.getString("username"));
                                    values.put("displayName", rs.getString("display_name"));
                                    values.put(
                                            "enabled", Boolean.toString(rs.getBoolean("enabled")));
                                    values.put("realName", rs.getString("real_name"));
                                    values.put("birthDate", rs.getString("birth_month"));
                                    values.put("region", rs.getString("region"));
                                    try {
                                        Map<String, String> stored =
                                                mapper.readValue(
                                                        rs.getString("profile_values"),
                                                        new TypeReference<>() {});
                                        values.putAll(stored);
                                    } catch (Exception invalid) {
                                        throw new IllegalStateException(
                                                "Stored profile values are invalid", invalid);
                                    }
                                    values.keySet().retainAll(profileFields);
                                    values.put("username", rs.getString("username"));
                                    values.put("displayName", rs.getString("display_name"));
                                    values.put(
                                            "enabled", Boolean.toString(rs.getBoolean("enabled")));
                                    result.put(id, values);
                                    return id;
                                })
                        .list();
        if (rows.size() != userIds.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "userNotFound");
        if (requestedFields.contains("roles")
                && hasOperation(actor, PlatformOperation.USER_READ_ROLES)) {
            includeRoles = true;
            for (UUID id : userIds) {
                if (!hasOperationUser(actor, id, PlatformOperation.USER_READ_ROLES))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }
        }
        if (includeRoles) {
            var roleRows =
                    jdbc.sql(
                                    "SELECT ur.user_id,string_agg(r.name,', ' ORDER BY r.name) AS names FROM platform_user_role ur JOIN platform_role r ON r.id=ur.role_id WHERE ur.user_id IN (:ids) GROUP BY ur.user_id")
                            .param("ids", userIds)
                            .query(
                                    (rs, n) ->
                                            Map.entry(
                                                    rs.getObject("user_id", UUID.class),
                                                    rs.getString("names")))
                            .list();
            roleRows.forEach(row -> result.get(row.getKey()).put("roles", row.getValue()));
        }
        if (requestedFields.contains("organizations") && includeOrganizations) {
            guard.requirePage(actor, PlatformPageId.ORGANIZATIONS);
            for (UUID id : userIds)
                guard.requireUser(actor, id, PlatformPageId.ORGANIZATIONS.accessPermission());
            var organizationRows =
                    jdbc.sql(
                                    "SELECT uo.user_id,string_agg(o.name,', ' ORDER BY o.name) AS names FROM platform_user_organization uo JOIN platform_organization o ON o.id=uo.organization_id WHERE uo.user_id IN (:ids) AND o.id IN (SELECT organization_id FROM platform_visible_organizations(:actor,:scopePermission)) GROUP BY uo.user_id")
                            .param("ids", userIds)
                            .param("actor", actor)
                            .param(
                                    "scopePermission",
                                    PlatformPageId.ORGANIZATIONS.accessPermission())
                            .query(
                                    (rs, n) ->
                                            Map.entry(
                                                    rs.getObject("user_id", UUID.class),
                                                    rs.getString("names")))
                            .list();
            organizationRows.forEach(
                    row -> result.get(row.getKey()).put("organizations", row.getValue()));
        }
        return result.entrySet().stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> {
                                    var filtered = new LinkedHashMap<String, String>();
                                    for (String key : requestedFields)
                                        if (entry.getValue().containsKey(key))
                                            filtered.put(
                                                    key,
                                                    java.util.Objects.requireNonNullElse(
                                                            entry.getValue().get(key), ""));
                                    return Map.copyOf(filtered);
                                }));
    }

    private boolean hasOperation(String actor, PlatformOperation operation) {
        try {
            guard.requireOperation(actor, operation);
            return true;
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw denied;
        }
    }

    private boolean hasOperationUser(String actor, UUID userId, PlatformOperation operation) {
        try {
            guard.requireOperationUser(actor, userId, operation);
            return true;
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw denied;
        }
    }

    private boolean hasPage(String actor, PlatformPageId page) {
        try {
            guard.requirePage(actor, page);
            return true;
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw denied;
        }
    }
}
