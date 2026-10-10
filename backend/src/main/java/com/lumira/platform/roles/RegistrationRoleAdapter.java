package com.lumira.platform.roles;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.RegistrationRolePort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Registration-only role allow-list, kept separate from administrator role HTTP operations. */
@Service
public class RegistrationRoleAdapter implements RegistrationRolePort {
    private static final Set<String> ALLOWED_AUTHORITIES =
            Set.of("platform:feedback:read", "platform:feedback:create");

    private final JdbcClient jdbc;

    public RegistrationRoleAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Candidate> candidates(PageRequest page) {
        String where =
                " WHERE strpos(lower(r.code),lower(:search))>0 OR strpos(lower(r.name),lower(:search))>0";
        var results =
                jdbc.sql(
                                "SELECT r.id,r.code,r.name,r.enabled,r.version,r.data_scope,"
                                        + "coalesce(array_agg(a.authority) FILTER (WHERE a.authority IS NOT NULL),'{}') AS authorities "
                                        + "FROM platform_role r LEFT JOIN platform_role_authority a ON a.role_id=r.id"
                                        + where
                                        + " GROUP BY r.id ORDER BY r.code,r.id LIMIT :limit OFFSET :offset")
                        .param("search", page.search())
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .query((rs, row) -> candidate(rs, rs.getArray("authorities")))
                        .list();
        long total =
                jdbc.sql("SELECT count(*) FROM platform_role r" + where)
                        .param("search", page.search())
                        .query(Long.class)
                        .single();
        return new PageResult<>(results, total, page.page(), page.pageSize());
    }

    @Override
    @Transactional(readOnly = true)
    public Candidate candidate(UUID roleId) {
        return jdbc.sql(
                        "SELECT r.id,r.code,r.name,r.enabled,r.version,r.data_scope,"
                                + "coalesce(array_agg(a.authority) FILTER (WHERE a.authority IS NOT NULL),'{}') AS authorities "
                                + "FROM platform_role r LEFT JOIN platform_role_authority a ON a.role_id=r.id "
                                + "WHERE r.id=:id GROUP BY r.id")
                .param("id", roleId)
                .query((rs, row) -> candidate(rs, rs.getArray("authorities")))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "registrationRoleNotFound"));
    }

    @Override
    @Transactional
    public void lockAndRequireEligible(UUID roleId) {
        var role =
                jdbc.sql(
                                "SELECT id,code,name,enabled,version,data_scope FROM platform_role WHERE id=:id FOR SHARE")
                        .param("id", roleId)
                        .query(
                                (rs, row) ->
                                        new LockedRole(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getBoolean("enabled"),
                                                rs.getLong("version"),
                                                rs.getString("data_scope")))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT, "registrationRoleIneligible"));
        Set<String> authorities =
                new HashSet<>(
                        jdbc.sql(
                                        "SELECT authority FROM platform_role_authority WHERE role_id=:id ORDER BY authority")
                                .param("id", roleId)
                                .query(String.class)
                                .list());
        String reason = ineligibility(role.enabled(), role.code(), role.dataScope(), authorities);
        if (reason != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "registrationRoleIneligible");
    }

    @Override
    @Transactional
    public void assignToNewAccount(UUID accountId, UUID roleId) {
        lockAndRequireEligible(roleId);
        jdbc.sql("INSERT INTO platform_user_role(user_id,role_id) VALUES(:account,:role)")
                .param("account", accountId)
                .param("role", roleId)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDefaultRole(UUID roleId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_registration_policy WHERE default_role_id=:id)")
                .param("id", roleId)
                .query(Boolean.class)
                .single();
    }

    private static Candidate candidate(java.sql.ResultSet rs, java.sql.Array array)
            throws java.sql.SQLException {
        var grants = new HashSet<String>();
        if (array != null) grants.addAll(Arrays.asList((String[]) array.getArray()));
        String reason =
                ineligibility(
                        rs.getBoolean("enabled"),
                        rs.getString("code"),
                        rs.getString("data_scope"),
                        grants);
        return new Candidate(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getLong("version"),
                reason == null,
                reason);
    }

    private static String ineligibility(
            boolean enabled, String code, String dataScope, Set<String> authorities) {
        if (!enabled) return "registrationRoleDisabled";
        if ("admin".equalsIgnoreCase(code)) return "registrationRoleAdministrator";
        if (!"SELF".equals(dataScope)) return "registrationRoleScopeNotAllowed";
        if (!ALLOWED_AUTHORITIES.containsAll(authorities))
            return "registrationRolePermissionsNotAllowed";
        return null;
    }

    private record LockedRole(
            UUID id, String code, String name, boolean enabled, long version, String dataScope) {}
}
