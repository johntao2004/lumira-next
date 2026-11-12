package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FileRoleQuotaDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private FileRoleQuotaRepository limits;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_file_role_quota_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("platform_user", "platform_role", "platform_user_role")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        TestSchema.foreignKeys("platform_user_role").forEach(jdbc::execute);
        limits = new FileRoleQuotaRepository(JdbcClient.create(source));
    }

    @AfterEach
    void cleanFixture() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void persistedRoleLimitsRemainEnforcedWithoutDirectoryServices() {
        UUID user = UUID.randomUUID(), enabled = UUID.randomUUID(), disabled = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(?,'quota-user','Quota','encoded')",
                user);
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,enabled,file_quota_bytes_limit,file_quota_files_limit) VALUES(?,'active','Active',true,300,7),(?,'disabled','Disabled',false,1,1)",
                enabled,
                disabled);
        jdbc.update(
                "INSERT INTO platform_user_role(user_id,role_id) VALUES(?,?),(?,?)",
                user,
                enabled,
                user,
                disabled);
        assertEquals(
                List.of(new FileRoleQuotaRepository.QuotaLimit(enabled, true, 300L, 7L)),
                limits.assignedQuotaLimits(user));
        assertEquals(2, limits.quotaLimits(Set.of(enabled, disabled)).size());
        assertTrue(limits.quotaLimits(Set.of()).isEmpty());
        var policy = new RoleQuotaPolicyAdapter(limits, new FileQuota(100, 2));
        assertEquals(
                new com.lumira.platform.common.FileQuotaPolicyPort.Limits(300, 7),
                policy.forUser(user));
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", enabled);
        assertEquals(
                new com.lumira.platform.common.FileQuotaPolicyPort.Limits(100, 2),
                policy.forUser(user));
    }
}
