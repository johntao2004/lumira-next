package com.lumira.testfixtures;

import com.lumira.platform.TestSchema;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public abstract class FileQueryFixture {
    protected JdbcTemplate jdbc;
    protected JdbcClient client;
    protected final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    private JdbcTemplate admin;
    private String schema;

    @BeforeEach
    protected void createFileQueryFixture() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_file_queries_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        client = JdbcClient.create(source);
        for (String table :
                java.util.List.of(
                        "platform_user",
                        "platform_file",
                        "platform_account_media",
                        "platform_feedback_file",
                        "platform_branding_file")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(?,'alice','Alice','fixture'),(?,'bob','Bob','fixture')",
                alice,
                bob);
        add(alice, "old.txt", "READY", "2026-01-01");
        add(alice, "new.txt", "READY", "2026-01-02");
        add(bob, "bob.txt", "READY", "2026-01-03");
        add(alice, "alice-trash.txt", "TRASHED", "2026-01-04");
        add(bob, "bob-trash.txt", "TRASHED", "2026-01-05");
        add(alice, "deleting.txt", "DELETING", "2026-01-06");
    }

    protected UUID add(UUID owner, String name, String state, String time) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_file(id,owner_id,storage_backend,original_name,state,size_bytes,sha256,created_at,trashed_at) VALUES(?,?,'postgres',?,?,12,?,CAST(? AS timestamptz),CASE WHEN ?='TRASHED' THEN CAST(? AS timestamptz) ELSE NULL END)",
                id,
                owner,
                name,
                state,
                "a".repeat(64),
                time,
                state,
                time);
        return id;
    }

    @AfterEach
    protected void removeFileQueryFixture() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
