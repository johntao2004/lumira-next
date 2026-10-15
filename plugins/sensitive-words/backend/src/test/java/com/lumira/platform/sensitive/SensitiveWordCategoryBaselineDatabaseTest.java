package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.dictionaries.DictionaryLookupRepository;
import com.lumira.platform.dictionaries.DictionaryLookupService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Verifies sensitive-word category choices against the current global baseline data. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SensitiveWordCategoryBaselineDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private SensitiveWordService service;
    private String schema;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_sensitive_category_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);

        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("platform_dictionary", "platform_dictionary_item")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        TestSchema.seeds("platform_dictionary").forEach(jdbc::execute);
        TestSchema.seeds("platform_dictionary_item").forEach(jdbc::execute);

        var dictionaries =
                new DictionaryLookupService(
                        new DictionaryLookupRepository(JdbcClient.create(dataSource), new JsonMapper()));
        service =
                new SensitiveWordService(
                        mock(SensitiveWordRepository.class),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        moduleId -> true,
                        dictionaries);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void baselineSeedsExposeOnlyEnabledDefaultAndImportedCategories() {
        assertEquals(
                List.of(
                        new SensitiveWordService.CategoryOption("DEFAULT", "默认分类", 10),
                        new SensitiveWordService.CategoryOption("IMPORTED", "导入词", 20)),
                service.categories("qa-admin"));

        jdbc.update(
                "UPDATE platform_dictionary_item SET enabled=false WHERE value='IMPORTED' "
                        + "AND dictionary_id=(SELECT id FROM platform_dictionary WHERE code=?)",
                SensitiveWordService.CATEGORY_DICTIONARY);
        assertEquals(
                List.of(new SensitiveWordService.CategoryOption("DEFAULT", "默认分类", 10)),
                service.categories("qa-admin"));
    }
}
