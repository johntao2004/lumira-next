package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.RegistrationRolePort;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AgreementSnapshotPort;
import com.lumira.platform.identity.api.SmsPasswordRecoveryPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RegistrationSettingsChangeReasonDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private RegistrationSettingsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_registration_reason_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE platform_registration_policy(
                    id smallint PRIMARY KEY,
                    version bigint NOT NULL,
                    enabled boolean NOT NULL,
                    channels text[] NOT NULL,
                    default_role_id uuid,
                    updated_by varchar(100) NOT NULL,
                    change_reason varchar(1000) NOT NULL CHECK (length(trim(change_reason)) > 0),
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.update(
                """
                INSERT INTO platform_registration_policy(
                    id,version,enabled,channels,default_role_id,updated_by,change_reason)
                VALUES (1,1,false,ARRAY[]::text[],NULL,'fixture','Initial registration policy')
                """);

        var agreements = mock(AgreementSnapshotPort.class);
        when(agreements.current()).thenReturn(new AgreementSnapshotPort.Document(1, "", ""));
        var maintenance = mock(MaintenanceService.class);
        when(maintenance.read())
                .thenReturn(new MaintenanceService.View(0, MaintenanceSettings.defaults()));
        ObjectProvider<CaptchaProofPort> captcha =
                (ObjectProvider<CaptchaProofPort>) mock(ObjectProvider.class);
        ObjectProvider<SmsPasswordRecoveryPort> smsRecovery =
                (ObjectProvider<SmsPasswordRecoveryPort>) mock(ObjectProvider.class);
        service =
                new RegistrationSettingsService(
                        JdbcClient.create(dataSource),
                        mock(AdministrationPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(RegistrationRolePort.class),
                        agreements,
                        mock(MailSender.class),
                        mock(SmsSender.class),
                        maintenance,
                        mock(AuditPort.class),
                        captcha,
                        smsRecovery);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void registrationSettingsSaveWithoutAReasonAndKeepTheVersionedAuditRecord() {
        var saved =
                service.save(
                        new RegistrationSettingsService.Input(1L, false, List.of(), null, null),
                        "qa-admin");

        assertEquals(2, saved.version());
        assertEquals(
                "Registration settings updated without a supplied change reason.",
                jdbc.queryForObject(
                        "SELECT change_reason FROM platform_registration_policy WHERE id=1",
                        String.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT version FROM platform_registration_policy WHERE id=1",
                        Integer.class));
    }
}
