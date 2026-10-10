package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SensitiveWordCategoryMigrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_sensitive_category_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);

        dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_dictionary (id UUID PRIMARY KEY, code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, structure VARCHAR(8) NOT NULL DEFAULT 'FLAT', remark VARCHAR(1000) NOT NULL DEFAULT '', version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE platform_dictionary_item (id UUID PRIMARY KEY, dictionary_id UUID NOT NULL, parent_id UUID, value VARCHAR(200) NOT NULL, label VARCHAR(200) NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE, remark VARCHAR(1000) NOT NULL DEFAULT '', version BIGINT NOT NULL DEFAULT 0, UNIQUE(dictionary_id, value))");
        jdbc.execute(
                "CREATE TABLE platform_sensitive_word (id UUID PRIMARY KEY, word VARCHAR(128) NOT NULL, category VARCHAR(64) NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.update(
                "INSERT INTO platform_sensitive_word(id,word,category,deleted) VALUES (?, ?, ?, ?), (?, ?, ?, ?), (?, ?, ?, ?)",
                UUID.randomUUID(),
                "active legacy",
                "DEFAULT",
                false,
                UUID.randomUUID(),
                "deleted legacy",
                "legacy:审计",
                true,
                UUID.randomUUID(),
                "imported legacy",
                "IMPORTED",
                false);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void seedsEveryRetainedCategoryAndDefaultsWithoutRewritingWordRows() throws Exception {
        List<Map<String, Object>> before =
                jdbc.queryForList(
                        "SELECT id, word, category, deleted FROM platform_sensitive_word ORDER BY id");
        String migration =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V15__sensitive_word_category_dictionary.sql"));

        executeMigration(migration);
        executeMigration(migration);

        assertEquals(
                before,
                jdbc.queryForList(
                        "SELECT id, word, category, deleted FROM platform_sensitive_word ORDER BY id"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_dictionary WHERE code='sensitive_word_category' AND enabled AND structure='FLAT'",
                        Integer.class));
        // Distinct categories: the two guaranteed choices plus the one retained legacy value.
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category'",
                        Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category' AND i.value IN ('DEFAULT','IMPORTED') AND i.enabled",
                        Integer.class));
        assertEquals(
                "legacy:审计",
                jdbc.queryForObject(
                        "SELECT label FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category' AND i.value='legacy:审计'",
                        String.class));
        assertTrue(
                jdbc.queryForObject(
                        "SELECT i.enabled FROM platform_dictionary_item i JOIN platform_dictionary d ON d.id=i.dictionary_id WHERE d.code='sensitive_word_category' AND i.value='legacy:审计'",
                        Boolean.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='platform_sensitive_word_category_recent'",
                        Integer.class));
    }

    private void executeMigration(String source) throws Exception {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(source.getBytes(StandardCharsets.UTF_8))));
        }
    }
}
