package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AuthorizationRevisionServiceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private AuthorizationRevisionService revisions;
    private String schema;
    private final UUID userId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID childOrganizationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_auth_revision_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        revisions = new AuthorizationRevisionService(JdbcClient.create(source));
        jdbc.execute(
                "CREATE TABLE platform_authority(user_id uuid NOT NULL, authority text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id uuid NOT NULL, role_id uuid NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_role(id uuid PRIMARY KEY, data_scope text NOT NULL, enabled boolean NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_role_authority(role_id uuid NOT NULL, authority text NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_organization(user_id uuid NOT NULL, organization_id uuid NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_organization(id uuid PRIMARY KEY, parent_id uuid, enabled boolean NOT NULL)");
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES(?,?),(?,?)",
                userId,
                "platform:users:update",
                userId,
                "platform:users:access");
        jdbc.update("INSERT INTO platform_user_role(user_id,role_id) VALUES(?,?)", userId, roleId);
        jdbc.update(
                "INSERT INTO platform_role(id,data_scope,enabled) VALUES(?,?,true)",
                roleId,
                "ORGANIZATION");
        jdbc.update(
                "INSERT INTO platform_role_authority(role_id,authority) VALUES(?,?),(?,?)",
                roleId,
                "platform:users:update",
                roleId,
                "platform:users:access");
        jdbc.update(
                "INSERT INTO platform_user_organization(user_id,organization_id) VALUES(?,?)",
                userId,
                organizationId);
        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,enabled) VALUES(?,NULL,true)",
                organizationId);
    }

    @AfterEach
    void cleanUp() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void scopeAndMembershipChangesRefreshRevisionAndGrantOrderDoesNot() {
        Set<String> grants =
                new LinkedHashSet<>(
                        java.util.List.of("platform:users:access", "platform:users:update"));
        Set<String> globalGrants = new LinkedHashSet<>(java.util.List.of("platform:users:access"));
        String organizationRevision = revisions.revision(userId, grants, globalGrants);

        jdbc.update("UPDATE platform_role SET data_scope='SUBTREE' WHERE id=?", roleId);
        String subtreeRevision = revisions.revision(userId, grants, globalGrants);
        assertNotEquals(organizationRevision, subtreeRevision);

        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,enabled) VALUES(?,?,true)",
                childOrganizationId,
                organizationId);
        String changedTreeRevision = revisions.revision(userId, grants, globalGrants);
        assertNotEquals(subtreeRevision, changedTreeRevision);

        jdbc.update(
                "INSERT INTO platform_user_organization(user_id,organization_id) VALUES(?,?)",
                userId,
                childOrganizationId);
        String changedMembershipRevision = revisions.revision(userId, grants, globalGrants);
        assertNotEquals(changedTreeRevision, changedMembershipRevision);

        Set<String> reorderedGrants =
                new LinkedHashSet<>(
                        java.util.List.of("platform:users:update", "platform:users:access"));
        assertEquals(
                changedMembershipRevision,
                revisions.revision(
                        userId,
                        reorderedGrants,
                        new LinkedHashSet<>(java.util.List.of("platform:users:access"))));
    }
}
