/**
 * 封装角色、权限、分配及配额持久化，按有效权限和范围筛选可分配项，同时保留既有分配供展示而不将其视作新委派资格。 Persists roles, permissions, assignments
 * and quotas, filtering assignable options by effective authority and scope while showing existing
 * assignments without treating them as new delegation eligibility.
 */
package com.lumira.platform.roles;

import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class RoleRepository {
    public record Role(
            UUID id,
            String code,
            String name,
            boolean enabled,
            long version,
            String dataScope,
            Long fileQuotaBytesLimit,
            Long fileQuotaFilesLimit,
            List<String> authorities,
            boolean defaultRegistrationRole) {}

    public record QuotaLimit(UUID roleId, boolean enabled, Long bytes, Long files) {}

    public record AssignmentOption(
            UUID id,
            String code,
            String name,
            boolean enabled,
            Long fileQuotaBytesLimit,
            Long fileQuotaFilesLimit,
            boolean assignable) {}

    /**
     * 按各最低数据范围等级汇总操作者权限；宽范围授权也计入较窄等级，因此 ALL 同时出现在 self、organization 与 subtree 集合中。
     *
     * <p>Permissions the actor holds at each minimum data-scope rank. A wider set includes every
     * narrower grant: an ALL grant is also present in {@code self}, {@code organization}, and
     * {@code subtree}.
     */
    public record AuthorityScopeCeiling(
            Set<String> self, Set<String> organization, Set<String> subtree, Set<String> all) {
        public AuthorityScopeCeiling {
            self = self == null ? Set.of() : Set.copyOf(self);
            organization = organization == null ? Set.of() : Set.copyOf(organization);
            subtree = subtree == null ? Set.of() : Set.copyOf(subtree);
            all = all == null ? Set.of() : Set.copyOf(all);
        }

        public static AuthorityScopeCeiling none() {
            return new AuthorityScopeCeiling(Set.of(), Set.of(), Set.of(), Set.of());
        }

        /**
         * 显式已知的 ALL 授权，主要用于构造测试数据。 Explicitly known ALL grants, primarily for constructing test
         * fixtures.
         */
        public static AuthorityScopeCeiling unrestricted(Set<String> authorities) {
            Set<String> held = authorities == null ? Set.of() : Set.copyOf(authorities);
            return new AuthorityScopeCeiling(held, held, held, held);
        }

        public int rank(String authority) {
            if (authority != null && all.contains(authority)) return 3;
            if (authority != null && subtree.contains(authority)) return 2;
            if (authority != null && organization.contains(authority)) return 1;
            if (authority != null && self.contains(authority)) return 0;
            return -1;
        }
    }

    private final JdbcClient jdbc;
    private static final String FILTER =
            " WHERE (:search='' OR strpos(lower(r.code),lower(:search))>0 OR strpos(lower(r.name),lower(:search))>0)";

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private PageFilterSql.Clause filters(PageRequest page) {
        return PageFilterSql.build(
                page, Map.of("code", "r.code", "name", "r.name", "enabled", "r.enabled"));
    }

    public long count(PageRequest p) {
        var f = filters(p);
        var params = new HashMap<String, Object>();
        params.put("search", p.search());
        params.putAll(f.parameters());
        return jdbc.sql("SELECT count(*) FROM platform_role r" + FILTER + f.sql())
                .params(params)
                .query(Long.class)
                .single();
    }

    public List<Role> list(PageRequest p) {
        var f = filters(p);
        var params = new HashMap<String, Object>();
        params.put("search", p.search());
        params.put("limit", p.pageSize());
        params.put("offset", p.offset());
        params.putAll(f.parameters());
        return jdbc.sql(
                        "SELECT r.id,r.code,r.name,r.enabled,r.version,r.data_scope,r.file_quota_bytes_limit,r.file_quota_files_limit,COALESCE((SELECT p.default_role_id=r.id FROM platform_registration_policy p WHERE p.id=1),false) AS default_registration_role FROM platform_role r"
                                + FILTER
                                + f.sql()
                                + " ORDER BY r.code,r.id LIMIT :limit OFFSET :offset")
                .params(params)
                .query(
                        (rs, n) ->
                                new Role(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("code"),
                                        rs.getString("name"),
                                        rs.getBoolean("enabled"),
                                        rs.getLong("version"),
                                        rs.getString("data_scope"),
                                        rs.getObject("file_quota_bytes_limit", Long.class),
                                        rs.getObject("file_quota_files_limit", Long.class),
                                        List.of(),
                                        rs.getBoolean("default_registration_role")))
                .list();
    }

    public com.lumira.platform.common.PageResult<AssignmentOption> assignmentOptions(
            PageRequest page,
            Set<UUID> assignedRoleIds,
            Set<String> delegableAuthorities,
            Set<String> activeAuthorities,
            AuthorityScopeCeiling scopeCeiling,
            boolean includeDelegableCandidates) {
        var f = filters(page);
        var params = new HashMap<String, Object>();
        params.put("search", page.search());
        params.putAll(f.parameters());

        Set<UUID> assigned = assignedRoleIds == null ? Set.of() : Set.copyOf(assignedRoleIds);
        if (!includeDelegableCandidates && assigned.isEmpty())
            return new com.lumira.platform.common.PageResult<>(
                    List.of(), 0, page.page(), page.pageSize());

        AuthorityScopeCeiling ceiling =
                scopeCeiling == null ? AuthorityScopeCeiling.none() : scopeCeiling;
        String delegationCheck =
                "NOT EXISTS (SELECT 1 FROM platform_role_authority ra "
                        + "WHERE ra.role_id=r.id "
                        + "AND ra.authority NOT LIKE 'platform:__v50:%' "
                        + "AND ra.authority IN (:activeAuthorities) "
                        + "AND (ra.authority NOT IN (:delegableAuthorities) "
                        + "OR NOT ((r.data_scope='SELF' AND ra.authority IN (:scopeSelf)) "
                        + "OR (r.data_scope='ORGANIZATION' AND ra.authority IN (:scopeOrganization)) "
                        + "OR (r.data_scope='SUBTREE' AND ra.authority IN (:scopeSubtree)) "
                        + "OR (r.data_scope='ALL' AND ra.authority IN (:scopeAll)))))";
        String assignmentFilter = "";
        if (includeDelegableCandidates) {
            params.put(
                    "delegableAuthorities",
                    delegableAuthorities.isEmpty() ? Set.of("") : delegableAuthorities);
            params.put(
                    "activeAuthorities",
                    activeAuthorities == null || activeAuthorities.isEmpty()
                            ? Set.of("")
                            : activeAuthorities);
            params.put("scopeSelf", ceiling.self().isEmpty() ? Set.of("") : ceiling.self());
            params.put(
                    "scopeOrganization",
                    ceiling.organization().isEmpty() ? Set.of("") : ceiling.organization());
            params.put(
                    "scopeSubtree", ceiling.subtree().isEmpty() ? Set.of("") : ceiling.subtree());
            params.put("scopeAll", ceiling.all().isEmpty() ? Set.of("") : ceiling.all());
            if (assigned.isEmpty()) {
                assignmentFilter = " AND " + delegationCheck;
            } else {
                params.put("assignedRoleIds", assigned);
                assignmentFilter = " AND (r.id IN (:assignedRoleIds) OR " + delegationCheck + ")";
            }
        } else {
            params.put("assignedRoleIds", assigned);
            assignmentFilter = " AND r.id IN (:assignedRoleIds)";
        }

        String where = FILTER + f.sql() + assignmentFilter;
        String assignableExpression = includeDelegableCandidates ? delegationCheck : "false";
        long total =
                jdbc.sql("SELECT count(*) FROM platform_role r" + where)
                        .params(params)
                        .query(Long.class)
                        .single();
        params.put("limit", page.pageSize());
        params.put("offset", page.offset());
        List<AssignmentOption> items =
                jdbc.sql(
                                "SELECT r.id,r.code,r.name,r.enabled,r.file_quota_bytes_limit,"
                                        + "r.file_quota_files_limit,("
                                        + assignableExpression
                                        + ") AS assignable FROM platform_role r"
                                        + where
                                        + " ORDER BY r.code,r.id LIMIT :limit OFFSET :offset")
                        .params(params)
                        .query(
                                (rs, n) ->
                                        new AssignmentOption(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getBoolean("enabled"),
                                                rs.getObject("file_quota_bytes_limit", Long.class),
                                                rs.getObject("file_quota_files_limit", Long.class),
                                                rs.getBoolean("assignable")))
                        .list();
        return new com.lumira.platform.common.PageResult<>(
                items, total, page.page(), page.pageSize());
    }

    public Role find(UUID id) {
        var role =
                jdbc.sql(
                                "SELECT r.id,r.code,r.name,r.enabled,r.version,r.data_scope,r.file_quota_bytes_limit,r.file_quota_files_limit,COALESCE((SELECT p.default_role_id=r.id FROM platform_registration_policy p WHERE p.id=1),false) AS default_registration_role FROM platform_role r WHERE r.id=:id")
                        .param("id", id)
                        .query(
                                (rs, n) ->
                                        new Role(
                                                id,
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getBoolean("enabled"),
                                                rs.getLong("version"),
                                                rs.getString("data_scope"),
                                                rs.getObject("file_quota_bytes_limit", Long.class),
                                                rs.getObject("file_quota_files_limit", Long.class),
                                                List.of(),
                                                rs.getBoolean("default_registration_role")))
                        .optional()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var grants =
                jdbc.sql(
                                "SELECT stored.authority FROM platform_role_authority stored "
                                        + "WHERE stored.role_id=:id "
                                        + "AND stored.authority NOT LIKE 'platform:__v50:%' "
                                        + "ORDER BY stored.authority")
                        .param("id", id)
                        .query(String.class)
                        .list();
        return new Role(
                id,
                role.code(),
                role.name(),
                role.enabled(),
                role.version(),
                role.dataScope(),
                role.fileQuotaBytesLimit(),
                role.fileQuotaFilesLimit(),
                grants,
                role.defaultRegistrationRole());
    }

    public void create(
            UUID id,
            String code,
            String name,
            boolean enabled,
            String dataScope,
            Long bytesLimit,
            Long filesLimit) {
        jdbc.sql(
                        "INSERT INTO platform_role(id,code,name,enabled,data_scope,file_quota_bytes_limit,file_quota_files_limit) VALUES(:id,:code,:name,:enabled,:scope,:bytes,:files)")
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("enabled", enabled)
                .param("scope", dataScope)
                .param("bytes", bytesLimit)
                .param("files", filesLimit)
                .update();
    }

    public void update(
            UUID id,
            String name,
            boolean enabled,
            String dataScope,
            Long bytesLimit,
            Long filesLimit) {
        jdbc.sql(
                        "UPDATE platform_role SET name=:name,enabled=:enabled,data_scope=:scope,file_quota_bytes_limit=:bytes,file_quota_files_limit=:files,version=version+1 WHERE id=:id")
                .param("id", id)
                .param("name", name)
                .param("enabled", enabled)
                .param("scope", dataScope)
                .param("bytes", bytesLimit)
                .param("files", filesLimit)
                .update();
    }

    public void authorities(UUID id, Set<String> grants) {
        jdbc.sql("DELETE FROM platform_role_authority WHERE role_id=:id").param("id", id).update();
        for (var grant : grants) {
            if (grant.startsWith("platform:__v50:")) continue;
            jdbc.sql("INSERT INTO platform_role_authority(role_id,authority) VALUES(:id,:grant)")
                    .param("id", id)
                    .param("grant", grant)
                    .update();
        }
    }

    public Set<String> actorAuthorities(String actor) {
        return new HashSet<>(
                jdbc.sql(
                                "SELECT a.authority FROM platform_effective_authority a JOIN platform_user u ON u.id=a.user_id WHERE u.username=:name AND u.enabled")
                        .param("name", actor)
                        .query(String.class)
                        .list());
    }

    public Set<String> actorEffectiveAuthorities(String actor) {
        return actorAuthorities(actor);
    }

    public Set<String> actorGlobalAuthorities(String actor) {
        return new HashSet<>(
                jdbc.sql(
                                "SELECT a.authority FROM platform_global_authority a JOIN platform_user u ON u.id=a.user_id WHERE u.username=:name AND u.enabled")
                        .param("name", actor)
                        .query(String.class)
                        .list());
    }

    public List<UUID> userRoles(UUID id) {
        return jdbc.sql("SELECT role_id FROM platform_user_role WHERE user_id=:id ORDER BY role_id")
                .param("id", id)
                .query(UUID.class)
                .list();
    }

    public List<QuotaLimit> assignedQuotaLimits(UUID userId) {
        return jdbc.sql(
                        "SELECT r.id,r.enabled,r.file_quota_bytes_limit,r.file_quota_files_limit FROM platform_user_role ur JOIN platform_role r ON r.id=ur.role_id WHERE ur.user_id=:id AND r.enabled ORDER BY r.id")
                .param("id", userId)
                .query(
                        (rs, n) ->
                                new QuotaLimit(
                                        rs.getObject("id", UUID.class),
                                        rs.getBoolean("enabled"),
                                        rs.getObject("file_quota_bytes_limit", Long.class),
                                        rs.getObject("file_quota_files_limit", Long.class)))
                .list();
    }

    public List<QuotaLimit> quotaLimits(Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        return jdbc.sql(
                        "SELECT id,enabled,file_quota_bytes_limit,file_quota_files_limit FROM platform_role WHERE id IN (:ids) ORDER BY id")
                .param("ids", roleIds)
                .query(
                        (rs, n) ->
                                new QuotaLimit(
                                        rs.getObject("id", UUID.class),
                                        rs.getBoolean("enabled"),
                                        rs.getObject("file_quota_bytes_limit", Long.class),
                                        rs.getObject("file_quota_files_limit", Long.class)))
                .list();
    }

    public void assign(UUID id, Set<UUID> roles) {
        jdbc.sql("DELETE FROM platform_user_role WHERE user_id=:id").param("id", id).update();
        for (var role : roles)
            jdbc.sql("INSERT INTO platform_user_role(user_id,role_id) VALUES(:id,:role)")
                    .param("id", id)
                    .param("role", role)
                    .update();
        jdbc.sql("UPDATE platform_user SET version=version+1 WHERE id=:id")
                .param("id", id)
                .update();
    }

    public boolean assigned(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform_user_role WHERE role_id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM platform_role WHERE id=:id").param("id", id).update();
    }

    public boolean hasAdministrators() {
        return jdbc.sql("SELECT platform_has_recovery_administrators()")
                .query(Boolean.class)
                .single();
    }

    public List<String> permissionHistory() {
        return jdbc.sql(
                        "SELECT authority FROM (SELECT authority FROM platform_role_authority WHERE authority NOT LIKE 'platform:__v50:%' "
                                + "UNION SELECT authority FROM platform_authority WHERE authority NOT LIKE 'platform:__v50:%') grants ORDER BY authority")
                .query(String.class)
                .list();
    }
}
