package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PluginDataRemovalTest {
    JdbcTemplate admin, jdbc;
    JdbcClient client;
    TransactionTemplate tx;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_plugin_data_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        client = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    PluginManifest.DataSet set(String table) {
        return new PluginManifest.DataSet(table, table, null, null);
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void deletesFeedbackAndLinksButPreservesOtherDraftsFilesAndMigrationHistory() {
        jdbc.execute(
                "CREATE TABLE platform_feedback(id int PRIMARY KEY);CREATE TABLE platform_feedback_file(id int REFERENCES platform_feedback);CREATE TABLE platform_account_draft(draft_key text);CREATE TABLE platform_file(id int);CREATE TABLE plugin_feedback_schema_history(id int)");
        jdbc.execute(
                "INSERT INTO platform_feedback VALUES(1);INSERT INTO platform_feedback_file VALUES(1);INSERT INTO platform_account_draft VALUES('feedback.create'),('other.create');INSERT INTO platform_file VALUES(1);INSERT INTO plugin_feedback_schema_history VALUES(1)");
        var sets =
                List.of(
                        set("platform_feedback_file"),
                        set("platform_feedback"),
                        new PluginManifest.DataSet(
                                "platform_account_draft",
                                "drafts",
                                "draft_key",
                                "feedback.create"));
        PluginDataRemoval.validate("feedback", sets);
        assertEquals(
                List.of(1L, 1L, 1L),
                PluginDataRemoval.counts(client, sets).stream()
                        .map(PluginDataRemoval.Count::count)
                        .toList());
        tx.executeWithoutResult(s -> PluginDataRemoval.delete(client, sets));
        assertEquals(0, count("platform_feedback"));
        assertEquals(0, count("platform_feedback_file"));
        assertEquals(1, count("platform_account_draft"));
        assertEquals(1, count("platform_file"));
        assertEquals(1, count("plugin_feedback_schema_history"));
    }

    @Test
    void cleanupFailureRollsBackEarlierDeletes() {
        jdbc.execute(
                "CREATE TABLE plugin_sample_a(id int PRIMARY KEY);CREATE TABLE plugin_sample_b(id int PRIMARY KEY);CREATE TABLE plugin_sample_ref(id int REFERENCES plugin_sample_b);INSERT INTO plugin_sample_a VALUES(1);INSERT INTO plugin_sample_b VALUES(1);INSERT INTO plugin_sample_ref VALUES(1)");
        var sets = List.of(set("plugin_sample_a"), set("plugin_sample_b"));
        PluginDataRemoval.validate("sample", sets);
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(s -> PluginDataRemoval.delete(client, sets)));
        assertEquals(1, count("plugin_sample_a"));
        assertEquals(1, count("plugin_sample_b"));
    }

    @Test
    void customLanguagesDeletedWithBooleanFilterWhileBuiltinsStay() {
        jdbc.execute(
                "CREATE TABLE platform_localization_locale(built_in boolean);INSERT INTO platform_localization_locale VALUES(true),(false)");
        var sets =
                List.of(
                        new PluginManifest.DataSet(
                                "platform_localization_locale",
                                "custom languages",
                                "built_in",
                                "false"));
        PluginDataRemoval.validate("localization", sets);
        assertEquals(1, PluginDataRemoval.counts(client, sets).getFirst().count());
        tx.executeWithoutResult(s -> PluginDataRemoval.delete(client, sets));
        assertEquals(1, count("platform_localization_locale"));
    }

    @Test
    void refusesCoreTablesUnscopedDraftsAndMigrationRecords() {
        for (var set :
                List.of(
                        set("platform_user"),
                        set("platform_account_draft"),
                        set("plugin_feedback_schema_history"),
                        new PluginManifest.DataSet(
                                "platform_account_draft", "drafts", "draft_key", "other.create")))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginDataRemoval.validate("feedback", List.of(set)));
    }
}
