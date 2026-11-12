package com.lumira.platform.feedback;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.plugins.dictionaries.DictionaryLookupRepository;
import com.lumira.plugins.dictionaries.DictionaryLookupService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FeedbackCatalogTest {
    JdbcTemplate admin, jdbc;
    FeedbackCatalog catalog;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_feedback_policy_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        catalog =
                new FeedbackCatalog(
                        new DictionaryLookupService(
                                new DictionaryLookupRepository(
                                        JdbcClient.create(ds), new JsonMapper())));
        for (String table : List.of("platform_dictionary", "platform_dictionary_item")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        TestSchema.seeds("platform_dictionary").forEach(jdbc::execute);
        TestSchema.seeds("platform_dictionary_item").forEach(jdbc::execute);
        jdbc.update(
                "DELETE FROM platform_dictionary_item item USING platform_dictionary dictionary "
                        + "WHERE item.dictionary_id=dictionary.id AND dictionary.code=?",
                FeedbackCatalog.CATEGORY);
        jdbc.update(
                "INSERT INTO platform_dictionary_item(id,dictionary_id,value,label,sort_order) SELECT gen_random_uuid(),id,'OTHER','其他',10 FROM platform_dictionary WHERE code=?",
                FeedbackCatalog.CATEGORY);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    void unavailable() {
        var e = assertThrows(ResponseStatusException.class, () -> catalog.load());
        assertEquals(503, e.getStatusCode().value());
        assertEquals("feedbackPolicyUnavailable", e.getReason());
    }

    void change(String dictionary, String value, String column, Object replacement) {
        jdbc.update(
                "UPDATE platform_dictionary_item SET "
                        + column
                        + "=? WHERE dictionary_id=(SELECT id FROM platform_dictionary WHERE code=?) AND value=?",
                replacement,
                dictionary,
                value);
    }

    @Test
    void loadsCurrentBaselineChoicesAndReflectsChangedDefaultsAndTerminalFlags() {
        var initial = catalog.load();
        assertEquals(
                List.of("OPEN", "PROCESSING", "RESOLVED", "CLOSED"),
                initial.statuses().stream().map(FeedbackCatalog.Choice::value).toList());
        assertEquals(
                List.of("OTHER"),
                initial.categories().stream().map(FeedbackCatalog.Choice::value).toList());
        assertEquals("NORMAL", initial.policy().defaultPriority());
        assertTrue(initial.policy().statuses().get("RESOLVED"));
        change(FeedbackCatalog.DEFAULT, "DEFAULT_PRIORITY", "label", "HIGH");
        change(FeedbackCatalog.STATUS, "PROCESSING", "remark", "TERMINAL");
        assertEquals("HIGH", catalog.load().policy().defaultPriority());
        assertTrue(catalog.load().policy().statuses().get("PROCESSING"));
        assertEquals("HIGH", catalog.load().policy().defaultPriority());
    }

    @Test
    void missingDisabledAndInvalidDefaultsFailExplicitly() {
        change(FeedbackCatalog.DEFAULT, "DEFAULT_PRIORITY", "label", "UNKNOWN");
        unavailable();
        change(FeedbackCatalog.DEFAULT, "DEFAULT_PRIORITY", "label", "NORMAL");
        change(FeedbackCatalog.PRIORITY, "NORMAL", "enabled", false);
        unavailable();
        change(FeedbackCatalog.PRIORITY, "NORMAL", "enabled", true);
        jdbc.update(
                "UPDATE platform_dictionary SET enabled=false WHERE code=?",
                FeedbackCatalog.STATUS);
        unavailable();
    }

    @Test
    void disabledAncestorsCannotSupplyEnabledChildren() {
        var parent =
                jdbc.queryForObject(
                        "SELECT id FROM platform_dictionary_item WHERE dictionary_id=(SELECT id FROM platform_dictionary WHERE code=?) AND value='LOW'",
                        UUID.class,
                        FeedbackCatalog.PRIORITY);
        change(FeedbackCatalog.PRIORITY, "NORMAL", "parent_id", parent);
        change(FeedbackCatalog.PRIORITY, "LOW", "enabled", false);
        unavailable();
        change(FeedbackCatalog.PRIORITY, "LOW", "enabled", true);
        assertEquals("NORMAL", catalog.load().policy().defaultPriority());
    }

    @Test
    void duplicateNormalizedCodesAreRejectedAndLargeCatalogsAreNotTruncated() {
        jdbc.update(
                "INSERT INTO platform_dictionary_item(id,dictionary_id,value,label,sort_order) SELECT gen_random_uuid(),id,'EXTRA_'||n,'Extra '||n,n+100 FROM platform_dictionary CROSS JOIN generate_series(1,105) n WHERE code=?",
                FeedbackCatalog.PRIORITY);
        assertEquals(109, catalog.load().priorities().size());
        jdbc.update(
                "INSERT INTO platform_dictionary_item(id,dictionary_id,value,label) SELECT gen_random_uuid(),id,'normal','Duplicate' FROM platform_dictionary WHERE code=?",
                FeedbackCatalog.PRIORITY);
        unavailable();
    }
}
