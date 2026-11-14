package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the real administration boundary can revalidate preview state inside PG read-only
 * transactions.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RolePreviewReadOnlyBoundaryDatabaseTest {
    private static final String READ_PERMISSION = "platform:users:access";
    private static final String PREVIEW_PERMISSION = "platform:roles:preview";
    private static final String ROLE_READ_PERMISSION = "platform:roles:access";
    private static final Set<String> ACTOR_AUTHORITIES =
            Set.of(READ_PERMISSION, PREVIEW_PERMISSION, ROLE_READ_PERMISSION);

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private String schema;
    private UUID actorId;
    private UUID targetRoleId;
    private String sessionPrimaryId;
    private String sessionId;
    private UUID contextRevision;
    private DataSourceTransactionManager transactions;
    private AdministrationGuard guard;
    private RolePreviewContextService boundary;
    private MockHttpServletRequest request;
    private RequestAuthorizationContext authorization;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_preview_readonly_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        client = JdbcClient.create(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_authentication_policy_lock(id integer PRIMARY KEY CHECK(id=1))");
        jdbc.update("INSERT INTO platform_authentication_policy_lock VALUES (1)");
        jdbc.execute(
                """
                CREATE TABLE spring_session(
                    primary_id character(36) PRIMARY KEY,
                    session_id character(36) NOT NULL UNIQUE,
                    principal_name varchar(100) NOT NULL,
                    expiry_time bigint NOT NULL)
                """);
        jdbc.execute(
                """
                CREATE TABLE platform_user(
                    id uuid PRIMARY KEY,username varchar(100) NOT NULL,enabled boolean NOT NULL,
                    deleted_at timestamptz)
                """);
        jdbc.execute(
                "CREATE TABLE platform_effective_authority(user_id uuid NOT NULL,authority text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_authority(user_id uuid NOT NULL,authority text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id uuid NOT NULL,role_id uuid NOT NULL)");
        jdbc.execute(
                """
                CREATE TABLE platform_role(
                    id uuid PRIMARY KEY,code varchar(100),name varchar(200),enabled boolean NOT NULL,
                    version bigint NOT NULL,data_scope varchar(20) NOT NULL)
                """);
        jdbc.execute(
                "CREATE TABLE platform_role_authority(role_id uuid NOT NULL,authority text NOT NULL)");
        jdbc.execute(
                """
                CREATE TABLE platform_role_preview_context(
                    session_primary_id character(36) NOT NULL,
                    actor_id uuid NOT NULL,
                    revision uuid NOT NULL,
                    state varchar(8) NOT NULL,
                    role_id uuid,
                    role_code varchar(100),
                    role_name varchar(200),
                    role_version bigint,
                    data_scope varchar(20),
                    started_at timestamptz,
                    expires_at timestamptz,
                    limited_by_actor boolean NOT NULL DEFAULT false,
                    blocked_reason varchar(40),
                    actor_authority_digest char(64),
                    role_authority_digest char(64),
                    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(session_primary_id,actor_id))
                """);

        actorId = UUID.randomUUID();
        targetRoleId = UUID.randomUUID();
        sessionPrimaryId = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
        contextRevision = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_user VALUES (?,?,true,NULL)", actorId, "admin");
        jdbc.update(
                "INSERT INTO spring_session VALUES (?,?,?,?)",
                sessionPrimaryId,
                sessionId,
                "admin",
                System.currentTimeMillis() + 600_000);
        jdbc.update(
                "INSERT INTO platform_role VALUES (?,?,?,true,1,'SELF')",
                targetRoleId,
                "qa-role",
                "QA role");
        jdbc.update(
                "INSERT INTO platform_role_authority VALUES (?,?)", targetRoleId, READ_PERMISSION);
        ACTOR_AUTHORITIES.forEach(
                permission -> {
                    jdbc.update("INSERT INTO platform_authority VALUES (?,?)", actorId, permission);
                    jdbc.update(
                            "INSERT INTO platform_effective_authority VALUES (?,?)",
                            actorId,
                            permission);
                });

        String actorDigest = actorDigest();
        String roleDigest = sha256(List.of(READ_PERMISSION));
        jdbc.update(
                """
                INSERT INTO platform_role_preview_context(
                    session_primary_id,actor_id,revision,state,role_id,role_code,role_name,role_version,
                    data_scope,started_at,expires_at,limited_by_actor,actor_authority_digest,role_authority_digest)
                VALUES (?,?,?,'ACTIVE',?,?,?,1,'SELF',now(),now()+interval '5 minutes',false,?,?)
                """,
                sessionPrimaryId,
                actorId,
                contextRevision,
                targetRoleId,
                "qa-role",
                "QA role",
                actorDigest,
                roleDigest);

        authorization =
                new RequestAuthorizationContext(
                        "admin",
                        actorId,
                        sessionPrimaryId,
                        ACTOR_AUTHORITIES,
                        ACTOR_AUTHORITIES,
                        Map.of(
                                READ_PERMISSION,
                                Set.of("ALL"),
                                PREVIEW_PERMISSION,
                                Set.of("ALL"),
                                ROLE_READ_PERMISSION,
                                Set.of("ALL")),
                        Set.of(READ_PERMISSION),
                        Set.of(READ_PERMISSION),
                        Map.of(READ_PERMISSION, Set.of("SELF")),
                        contextRevision.toString(),
                        contextRevision.toString(),
                        true,
                        new RequestAuthorizationContext.Preview(
                                "ACTIVE",
                                targetRoleId,
                                "QA role",
                                1,
                                OffsetDateTime.now().minusMinutes(1),
                                OffsetDateTime.now().plusMinutes(5),
                                false,
                                null));
        request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/platform/users");
        request.addHeader(RolePreviewContextService.REVISION_HEADER, contextRevision.toString());

        transactions = new DataSourceTransactionManager(dataSource);
        transactions.setEnforceReadOnly(true);
        boundary =
                new RolePreviewContextService(
                        client,
                        mock(AuditPort.class),
                        null,
                        new IdentityAuthenticationPolicyWriteLock(client));
        guard = new AdministrationGuard(client, boundary);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void ordinaryRequestRetainsRealGlobalScopesWithoutAnActivePreview() {
        prepareOrdinaryRequest();
        var resolved = resolveOrdinaryRequest();
        assertFalse(resolved.previewActive());
        assertEquals("NONE", resolved.preview().state());
        assertEquals(ACTOR_AUTHORITIES, resolved.effectiveGlobalAuthorities());
        assertEquals(resolved.realScopes(), resolved.effectiveScopes());
        for (String permission : ACTOR_AUTHORITIES)
            assertEquals(Set.of("ALL"), resolved.effectiveScopes().get(permission));
    }

    @Test
    void ordinaryRequestPreservesPerPermissionRoleScopesAndObservesRevocation() {
        prepareOrdinaryRequest();
        jdbc.update(
                "DELETE FROM platform_authority WHERE user_id=? AND authority=?",
                actorId,
                READ_PERMISSION);
        jdbc.update("INSERT INTO platform_user_role VALUES (?,?)", actorId, targetRoleId);
        for (String scope : List.of("SELF", "ORGANIZATION", "SUBTREE", "ALL")) {
            jdbc.update("UPDATE platform_role SET data_scope=? WHERE id=?", scope, targetRoleId);
            var resolved = resolveOrdinaryRequest();
            assertEquals(Set.of(scope), resolved.effectiveScopes().get(READ_PERMISSION));
            assertEquals(
                    "ALL".equals(scope),
                    resolved.effectiveGlobalAuthorities().contains(READ_PERMISSION));
            assertEquals(Set.of("ALL"), resolved.effectiveScopes().get(PREVIEW_PERMISSION));
        }
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", targetRoleId);
        var disabled = resolveOrdinaryRequest();
        assertFalse(disabled.effectiveScopes().containsKey(READ_PERMISSION));
        assertFalse(disabled.effectiveGlobalAuthorities().contains(READ_PERMISSION));
        jdbc.update("UPDATE platform_role SET enabled=true WHERE id=?", targetRoleId);
        jdbc.update(
                "DELETE FROM platform_user_role WHERE user_id=? AND role_id=?",
                actorId,
                targetRoleId);
        assertFalse(resolveOrdinaryRequest().effectiveScopes().containsKey(READ_PERMISSION));
    }

    private void prepareOrdinaryRequest() {
        jdbc.update(
                "UPDATE platform_role_preview_context SET state='NONE' WHERE session_primary_id=?",
                sessionPrimaryId);
        request.setSession(new MockHttpSession(null, sessionId));
    }

    private RequestAuthorizationContext resolveOrdinaryRequest() {
        return new TransactionTemplate(transactions)
                .execute(status -> boundary.resolve(request, "admin", actorId, ACTOR_AUTHORITIES));
    }

    @Test
    void administrationGuardRevalidatesActivePreviewInReadOnlyTransactionWithoutRowLocks() {
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        Set<String> actual =
                withRequestContext(
                        () -> tx.execute(status -> guard.require("admin", READ_PERMISSION)));
        assertEquals(Set.of(READ_PERMISSION), actual);

        // The next request must observe live role state, even though its validation transaction is
        // read-only.
        jdbc.update("UPDATE platform_role SET version=version+1 WHERE id=?", targetRoleId);
        ResponseStatusException rejected =
                withRequestContext(
                        () ->
                                tx.execute(
                                        status -> {
                                            ResponseStatusException failure =
                                                    assertThrows(
                                                            ResponseStatusException.class,
                                                            () ->
                                                                    guard.require(
                                                                            "admin",
                                                                            READ_PERMISSION));
                                            return failure;
                                        }));
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode());
        assertEquals("rolePreviewBlocked", rejected.getReason());
    }

    @Test
    void staleRevisionIsRejectedByTheLiveReadOnlyContextRead() {
        UUID nextRevision = UUID.randomUUID();
        jdbc.update(
                "UPDATE platform_role_preview_context SET revision=? WHERE session_primary_id=?",
                nextRevision,
                sessionPrimaryId);
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        ResponseStatusException rejected =
                withRequestContext(
                        () ->
                                tx.execute(
                                        status -> {
                                            ResponseStatusException failure =
                                                    assertThrows(
                                                            ResponseStatusException.class,
                                                            () ->
                                                                    guard.require(
                                                                            "admin",
                                                                            READ_PERMISSION));
                                            return failure;
                                        }));
        assertEquals(HttpStatus.CONFLICT, rejected.getStatusCode());
        assertEquals("authorizationContextChanged", rejected.getReason());
    }

    @Test
    void administrationGuardRevalidatesWriteBoundaryWithCombinedPolicyAndSessionShareLock() {
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(false);
        Set<String> actual =
                withRequestContext(
                        () -> tx.execute(status -> guard.require("admin", READ_PERMISSION)));
        assertEquals(Set.of(READ_PERMISSION), actual);
    }

    @Test
    void preloadedNoneContextWithOrganizationRoleReportsNotLimitedByActor() {
        UUID orgRoleId = UUID.randomUUID();
        String accessPermission = "platform:users:access";
        jdbc.update(
                "INSERT INTO platform_role VALUES (?,?,?,true,1,'ORGANIZATION')",
                orgRoleId,
                "org-role",
                "Org Role");
        jdbc.update(
                "INSERT INTO platform_role_authority VALUES (?,?)", orgRoleId, accessPermission);
        jdbc.update("INSERT INTO platform_user_role VALUES (?,?)", actorId, orgRoleId);
        jdbc.update(
                "INSERT INTO platform_effective_authority VALUES (?,?)", actorId, accessPermission);

        UUID noneRevision = UUID.randomUUID();
        jdbc.update(
                """
                UPDATE platform_role_preview_context
                   SET revision=?,state='NONE',role_id=NULL,role_code=NULL,role_name=NULL,
                       role_version=NULL,data_scope=NULL,started_at=NULL,expires_at=NULL,
                       limited_by_actor=false,blocked_reason=NULL,actor_authority_digest=NULL,
                       role_authority_digest=NULL,updated_at=CURRENT_TIMESTAMP
                 WHERE session_primary_id=? AND actor_id=?
                """,
                noneRevision,
                sessionPrimaryId,
                actorId);

        Set<String> allAuthorities =
                Set.of(READ_PERMISSION, PREVIEW_PERMISSION, ROLE_READ_PERMISSION, accessPermission);
        Set<String> globalAuthorities =
                Set.of(READ_PERMISSION, PREVIEW_PERMISSION, ROLE_READ_PERMISSION);

        var noneRequest = new MockHttpServletRequest();
        noneRequest.setMethod("GET");
        noneRequest.setRequestURI("/api/platform/role-previews/candidates");
        noneRequest.addHeader(RolePreviewContextService.REVISION_HEADER, noneRevision.toString());

        RequestAuthorizationContext noneContext =
                boundary.resolvePreloadedNoneContext(
                        noneRequest,
                        "admin",
                        actorId,
                        sessionPrimaryId,
                        noneRevision,
                        allAuthorities,
                        globalAuthorities);

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(noneRequest));
        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "admin", actorId, null, noneContext))) {
            var tx = new TransactionTemplate(transactions);
            tx.setReadOnly(false);
            tx.execute(
                    status -> {
                        var candidates =
                                boundary.candidates(noneRequest, new PageRequest(1, 10, ""));
                        var candidate =
                                candidates.items().stream()
                                        .filter(c -> orgRoleId.equals(c.id()))
                                        .findFirst()
                                        .orElseThrow();
                        assertFalse(
                                candidate.limitedByActor(),
                                "candidates() must not be limited by actor");

                        var enterView =
                                boundary.enter(noneRequest, orgRoleId, 1L, noneRevision.toString());
                        assertNotNull(enterView.preview());
                        assertFalse(
                                enterView.preview().limitedByActor(),
                                "enter() must not be limited by actor");
                        return null;
                    });
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private <T> T withRequestContext(java.util.function.Supplier<T> action) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "admin", actorId, null, authorization))) {
            return action.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private String actorDigest() {
        var parts = new java.util.ArrayList<String>();
        ACTOR_AUTHORITIES.stream().sorted().forEach(permission -> parts.add("A:" + permission));
        ACTOR_AUTHORITIES.stream().sorted().forEach(permission -> parts.add("G:" + permission));
        ACTOR_AUTHORITIES.stream()
                .sorted()
                .forEach(permission -> parts.add("S:" + permission + ":ALL"));
        return sha256(parts);
    }

    private static String sha256(List<String> values) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            String.join("\n", values)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
