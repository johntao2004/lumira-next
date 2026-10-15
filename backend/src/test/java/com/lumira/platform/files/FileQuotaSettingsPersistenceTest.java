package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.FileQuotaPolicyPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.roles.RoleQuotaPolicyAdapter;
import com.lumira.platform.roles.RoleRepository;
import com.lumira.platform.settings.SettingDocuments;
import com.lumira.platform.settings.SettingStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL persistence coverage isolated to a temporary schema. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FileQuotaSettingsPersistenceTest {
    private JdbcTemplate admin;
    private String schema;
    private SettingStore store;
    private FileQuotaSettingsService service;
    private AdministrationPort guard;
    private AuditPort audit;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_file_quota_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(TestSchema.table("platform_setting"));
        TestSchema.indexes("platform_setting").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_setting_revision"));
        TestSchema.indexes("platform_setting_revision").forEach(jdbc::execute);
        guard = mock(AdministrationPort.class);
        audit = mock(AuditPort.class);
        store = new SettingStore(JdbcClient.create(dataSource), guard, audit);
        service =
                new FileQuotaSettingsService(
                        new SettingDocuments(store, new ObjectMapper()),
                        new FileQuota(1_024, 20),
                        guard);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void environmentFallbackBecomesSavedRuntimeDefaultImmediatelyAndRejectsStaleWrites() {
        String actor = "quota-admin";
        UUID userId = UUID.randomUUID();
        var roles = mock(RoleRepository.class);
        when(roles.assignedQuotaLimits(userId)).thenReturn(List.of());
        var policy = new RoleQuotaPolicyAdapter(roles, service);
        var fallback = new FileQuotaSettings(1_024, 20);

        assertEquals(new FileQuotaSettingsService.View(0, fallback), service.read(actor));
        assertEquals(new FileQuotaPolicyPort.Limits(1_024, 20), policy.forUser(userId));

        var saved = service.save(0, new FileQuotaSettings(2_048, 30), actor);
        assertEquals(new FileQuotaSettingsService.View(1, new FileQuotaSettings(2_048, 30)), saved);
        assertEquals(new FileQuotaPolicyPort.Limits(2_048, 30), service.current());
        assertEquals(new FileQuotaPolicyPort.Limits(2_048, 30), policy.forUser(userId));

        var reloaded =
                new FileQuotaSettingsService(
                        new SettingDocuments(store, new ObjectMapper()),
                        new FileQuota(1_024, 20),
                        guard);
        assertEquals(saved, reloaded.read(actor));
        assertEquals(1, store.history("file-quota", new PageRequest(1, 20, "")).total());

        var conflict =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.save(0, new FileQuotaSettings(3_072, 40), actor));
        assertEquals("staleRecord", conflict.getReason());
        assertEquals(saved, service.read(actor));

        var unlimited = service.save(1, new FileQuotaSettings(0, 0), actor);
        assertEquals(new FileQuotaSettingsService.View(2, new FileQuotaSettings(0, 0)), unlimited);
        assertEquals(new FileQuotaPolicyPort.Limits(0, 0), policy.forUser(userId));
        assertDoesNotThrow(() -> new FileQuota(0, 0).requireCapacity(0, 100, 1));
        assertEquals(unlimited, reloaded.read(actor));
        assertEquals(2, store.history("file-quota", new PageRequest(1, 20, "")).total());

        verify(guard, times(4)).requirePageGlobal(actor, PlatformPageId.FILES_ALL);
        verify(guard, times(3))
                .requireOperationGlobal(actor, PlatformOperation.FILES_ALL_UPDATE_QUOTA);
        verify(audit, times(2))
                .append(
                        org.mockito.ArgumentMatchers.eq(actor),
                        org.mockito.ArgumentMatchers.eq("setting.update"),
                        org.mockito.ArgumentMatchers.any(UUID.class));
    }
}
