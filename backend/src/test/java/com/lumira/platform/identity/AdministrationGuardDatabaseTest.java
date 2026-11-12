package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AdministrationGuardDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private AdministrationGuard guard;
    private String schema;
    private UUID actor;
    private UUID globalRole;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_guard_grants_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        guard = new AdministrationGuard(JdbcClient.create(source));
        for (String table :
                List.of(
                        "platform_user",
                        "platform_passkey_credential",
                        "platform_authority",
                        "platform_role",
                        "platform_user_role",
                        "platform_role_authority",
                        "platform_permission_retired_authority"))
            jdbc.execute(TestSchema.table(table));
        for (String view : List.of("platform_effective_authority", "platform_global_authority"))
            jdbc.execute(TestSchema.view(view));
        actor = UUID.randomUUID();
        globalRole = UUID.randomUUID();
        UUID scopedRole = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES (?,'alice','Alice','fixture')",
                actor);
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,data_scope) VALUES (?,'global','Global','ALL'),(?,'scoped','Scoped','SELF')",
                globalRole,
                scopedRole);
        jdbc.update(
                "INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?),(?,?)",
                actor,
                globalRole,
                actor,
                scopedRole);
        jdbc.update(
                "INSERT INTO platform_role_authority(role_id,authority) VALUES (?,'platform:dictionaries:create'),(?,'platform:dictionaries:update')",
                globalRole,
                scopedRole);
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES (?,'platform:dictionaries:delete')",
                actor);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void keepsDirectAndRoleScopesIsolatedFromOtherAccounts() {
        jdbc.execute(
                "INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'other_'||n,'Other','fixture' FROM generate_series(1,1000) n");
        jdbc.execute(
                "INSERT INTO platform_authority(user_id,authority) SELECT id,'foreign:only' FROM platform_user WHERE username<>'alice'");
        assertEquals(
                Set.of(
                        "platform:dictionaries:create",
                        "platform:dictionaries:update",
                        "platform:dictionaries:delete"),
                guard.require("alice", "platform:dictionaries:create"));
        assertTrue(guard.hasGlobal("alice", "platform:dictionaries:create"));
        assertTrue(guard.hasGlobal("alice", "platform:dictionaries:delete"));
        assertFalse(guard.hasGlobal("alice", "platform:dictionaries:update"));
        assertFalse(guard.hasGlobal("alice", "foreign:only"));
    }

    @Test
    void observesDisabledRolesAndRevocationOnTheNextCheck() {
        assertTrue(guard.hasGlobal("alice", "platform:dictionaries:create"));
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", globalRole);
        assertFalse(guard.hasGlobal("alice", "platform:dictionaries:create"));
        assertThrows(
                ResponseStatusException.class,
                () -> guard.require("alice", "platform:dictionaries:create"));
        jdbc.update("DELETE FROM platform_authority WHERE user_id=?", actor);
        assertFalse(guard.hasGlobal("alice", "platform:dictionaries:delete"));
        assertThrows(
                ResponseStatusException.class,
                () -> guard.require("alice", "platform:dictionaries:delete"));
    }

    @Test
    void excludesRetiredAndLegacyAuthoritiesFromBothGrantKinds() {
        jdbc.update(
                "INSERT INTO platform_permission_retired_authority(authority) VALUES ('platform:dictionaries:create'),('platform:dictionaries:delete')");
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES (?,'platform:__v50:legacy')",
                actor);
        for (String permission :
                List.of(
                        "platform:dictionaries:create",
                        "platform:dictionaries:delete",
                        "platform:__v50:legacy")) {
            assertFalse(guard.hasGlobal("alice", permission));
            assertThrows(ResponseStatusException.class, () -> guard.require("alice", permission));
        }
        assertEquals(
                Set.of("platform:dictionaries:update"),
                guard.require("alice", "platform:dictionaries:update"));
    }

    @Test
    void rejectsDisabledOrMissingActors() {
        jdbc.update("UPDATE platform_user SET enabled=false WHERE id=?", actor);
        for (String name : List.of("alice", "missing")) {
            assertFalse(guard.hasGlobal(name, "platform:dictionaries:create"));
            assertThrows(
                    ResponseStatusException.class,
                    () -> guard.require(name, "platform:dictionaries:create"));
        }
    }

    @Test
    void currentRequestGrantsStayBoundToTheActorAndObserveRevocation() {
        jdbc.execute(
                "INSERT INTO platform_user(id,username,display_name,password_hash) SELECT gen_random_uuid(),'other_'||n,'Other','fixture' FROM generate_series(1,1000) n");
        jdbc.execute(
                "INSERT INTO platform_authority(user_id,authority) SELECT id,'foreign:only' FROM platform_user WHERE username<>'alice'");
        var rows = currentRequestGrants("alice");
        assertEquals(
                Set.of(
                        new ActorGrant(actor, "platform:dictionaries:create"),
                        new ActorGrant(actor, "platform:dictionaries:update"),
                        new ActorGrant(actor, "platform:dictionaries:delete")),
                Set.copyOf(rows));
        assertEquals(3, rows.size());
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", globalRole);
        jdbc.update("DELETE FROM platform_authority WHERE user_id=?", actor);
        assertEquals(
                List.of(new ActorGrant(actor, "platform:dictionaries:update")),
                currentRequestGrants("alice"));
        jdbc.execute(
                "INSERT INTO platform_permission_retired_authority(authority) VALUES ('platform:dictionaries:update')");
        assertEquals(List.of(new ActorGrant(actor, null)), currentRequestGrants("alice"));
    }

    @Test
    void currentRequestDistinguishesAnAccountWithoutGrantsFromAnInvalidAccount() {
        jdbc.update("DELETE FROM platform_authority WHERE user_id=?", actor);
        jdbc.update("DELETE FROM platform_user_role WHERE user_id=?", actor);
        assertEquals(List.of(new ActorGrant(actor, null)), currentRequestGrants("alice"));
        assertTrue(currentRequestGrants("missing").isEmpty());
        jdbc.update("UPDATE platform_user SET enabled=false WHERE id=?", actor);
        assertTrue(currentRequestGrants("alice").isEmpty());
        jdbc.update("UPDATE platform_user SET enabled=true,deleted_at=now() WHERE id=?", actor);
        assertTrue(currentRequestGrants("alice").isEmpty());
    }

    private List<ActorGrant> currentRequestGrants(String username) {
        return JdbcClient.create(jdbc.getDataSource())
                .sql(CurrentAuthorityFilter.CONTEXT_QUERY)
                .param("name", username)
                .query(
                        (rs, row) ->
                                new ActorGrant(
                                        rs.getObject("id", UUID.class), rs.getString("authority")))
                .list();
    }

    private record ActorGrant(UUID id, String authority) {}
}
