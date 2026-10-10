package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real published schema guards against policy queries drifting from user and role storage. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RoleContentPolicyRepositoryTest {
    JdbcTemplate admin, jdbc;
    String schema;
    RoleContentPolicyRepository policies;
    UUID user = UUID.randomUUID(), role = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                login = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, login, password));
        schema = "qa_policy_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        login,
                        password);
        jdbc = new JdbcTemplate(ds);
        String baseline =
                new ClassPathResource("db/migration/V1__platform_initialization.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        for (String table :
                java.util.List.of("platform_user", "platform_role", "platform_user_role")) {
            var definition =
                    Pattern.compile(
                                    "CREATE TABLE public\\." + table + " \\(.*?\\n\\);",
                                    Pattern.DOTALL)
                            .matcher(baseline);
            assertTrue(definition.find(), "Published table definition missing: " + table);
            jdbc.execute(definition.group().replace("public.", ""));
        }
        jdbc.execute("ALTER TABLE platform_role ADD PRIMARY KEY(id)");
        jdbc.execute(
                new ClassPathResource("db/migration/V4__user_soft_delete.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(
                new ClassPathResource("db/migration/V19__role_content_policy.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
        policies = new RoleContentPolicyRepository(JdbcClient.create(ds));
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash,enabled) VALUES(?,?,?,?,true)",
                user,
                "qa_policy",
                "Policy QA",
                "unused");
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,enabled) VALUES(?,?,?,true)",
                role,
                "qa_policy",
                "Policy QA");
        jdbc.update("INSERT INTO platform_user_role(user_id,role_id) VALUES(?,?)", user, role);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void freshRoleDefaultsOnAndPolicyUsesOptimisticVersion() {
        var rows = policies.forActor("qa_policy");
        assertEquals(1, rows.size());
        assertEquals("ON", rows.getFirst().mode());
        assertEquals(0, rows.getFirst().version());
        assertTrue(policies.save(role, "OFF", 0, "qa_policy"));
        assertFalse(policies.save(role, "ON", 0, "qa_policy"));
        assertEquals("OFF", policies.forActor("qa_policy").getFirst().mode());
        assertTrue(policies.save(role, "ON", 1, "qa_policy"));
        assertFalse(policies.save(role, "OFF", 1, "qa_policy"));
        assertEquals("ON", policies.forActor("qa_policy").getFirst().mode());
    }

    @Test
    void disabledRolesAndDeletedOrDisabledUsersDoNotContributePolicies() {
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", role);
        assertTrue(policies.forActor("qa_policy").isEmpty());
        jdbc.update("UPDATE platform_role SET enabled=true WHERE id=?", role);
        jdbc.update("UPDATE platform_user SET deleted_at=now() WHERE id=?", user);
        assertTrue(policies.forActor("qa_policy").isEmpty());
        jdbc.update("UPDATE platform_user SET deleted_at=null,enabled=false WHERE id=?", user);
        assertTrue(policies.forActor("qa_policy").isEmpty());
    }
}
