package com.lumira.platform.identity;

import com.lumira.platform.common.AuthorizationRevision;
import com.lumira.platform.common.AuthorizationRevisionPort;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationRevisionService implements AuthorizationRevisionPort {
    private final JdbcClient jdbc;

    public AuthorizationRevisionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String revision(
            UUID userId, Set<String> effectiveAuthorities, Set<String> globalAuthorities) {
        Set<String> directAuthorities =
                Set.copyOf(
                        jdbc.sql("SELECT authority FROM platform_authority WHERE user_id=:userId")
                                .param("userId", userId)
                                .query(String.class)
                                .list());
        var roleGrantRows =
                jdbc.sql(
                                "SELECT r.id,r.data_scope,ra.authority FROM platform_user_role ur "
                                        + "JOIN platform_role r ON r.id=ur.role_id AND r.enabled "
                                        + "LEFT JOIN platform_role_authority ra ON ra.role_id=r.id "
                                        + "WHERE ur.user_id=:userId ORDER BY r.id,ra.authority")
                        .param("userId", userId)
                        .query(
                                (rs, rowNum) ->
                                        new RoleGrantRow(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("data_scope"),
                                                rs.getString("authority")))
                        .list();
        var roleScopes = new TreeMap<UUID, String>();
        var roleAuthorities = new TreeMap<UUID, Set<String>>();
        for (var row : roleGrantRows) {
            roleScopes.put(row.roleId(), row.dataScope());
            var grants = roleAuthorities.computeIfAbsent(row.roleId(), ignored -> new TreeSet<>());
            if (row.authority() != null) grants.add(row.authority());
        }
        var activeRoles =
                roleScopes.entrySet().stream()
                        .map(
                                entry ->
                                        new AuthorizationRevision.ActiveRole(
                                                entry.getKey(),
                                                entry.getValue(),
                                                roleAuthorities.getOrDefault(
                                                        entry.getKey(), Set.of())))
                        .toList();
        Set<UUID> memberships =
                Set.copyOf(
                        jdbc.sql(
                                        "SELECT organization_id FROM platform_user_organization WHERE user_id=:userId")
                                .param("userId", userId)
                                .query(UUID.class)
                                .list());
        List<AuthorizationRevision.OrganizationNode> organizationTree =
                activeRoles.stream().anyMatch(role -> "SUBTREE".equals(role.dataScope()))
                        ? jdbc.sql("SELECT id,parent_id,enabled FROM platform_organization")
                                .query(
                                        (rs, rowNum) ->
                                                new AuthorizationRevision.OrganizationNode(
                                                        rs.getObject("id", UUID.class),
                                                        rs.getObject("parent_id", UUID.class),
                                                        rs.getBoolean("enabled")))
                                .list()
                        : List.of();
        return AuthorizationRevision.from(
                new AuthorizationRevision.Facts(
                        userId,
                        effectiveAuthorities,
                        globalAuthorities,
                        directAuthorities,
                        activeRoles,
                        memberships,
                        organizationTree));
    }

    private record RoleGrantRow(UUID roleId, String dataScope, String authority) {}
}
