package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AiModelInputSettingsDatabaseTest {
    private JdbcTemplate admin;
    private String schema;
    private AiModelInputSettings settings;
    private TransactionTemplate transactions;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_model_formats_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(TestSchema.table("platform_setting"));
        settings = new AiModelInputSettings(JdbcClient.create(source), new ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void persistsPerModelSelectionIncludingEmptyAndKeepsProvidersIsolated() {
        var id = UUID.randomUUID();
        var other = UUID.randomUUID();
        var policy = Map.of("flash", List.of("png", "pdf"), "text-only", List.<String>of());
        transactions.executeWithoutResult(status -> settings.save(id, policy, "admin"));
        assertEquals(policy, settings.read(id));
        assertEquals(Map.of(), settings.read(other));
        transactions.executeWithoutResult(
                status -> settings.save(id, Map.of("flash", List.of("pdf")), "admin"));
        assertEquals(Map.of("flash", List.of("pdf")), settings.read(id));
    }

    @Test
    void rollsBackWithProviderTransactionAndDeletesOnlyTheSelectedProvider() {
        var id = UUID.randomUUID();
        var other = UUID.randomUUID();
        transactions.executeWithoutResult(
                status -> {
                    settings.save(id, Map.of("m", List.of("png")), "admin");
                    settings.save(other, Map.of("m", List.of("pdf")), "admin");
                });
        assertThrows(
                IllegalStateException.class,
                () ->
                        transactions.executeWithoutResult(
                                status -> {
                                    settings.save(id, Map.of("m", List.of()), "admin");
                                    throw new IllegalStateException("rollback fixture");
                                }));
        assertEquals(List.of("png"), settings.read(id).get("m"));
        transactions.executeWithoutResult(status -> settings.delete(id));
        assertEquals(Map.of(), settings.read(id));
        assertEquals(List.of("pdf"), settings.read(other).get("m"));
    }
}
