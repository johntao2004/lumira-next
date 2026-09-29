package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class SettingStoreTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    private final JdbcClient.MappedQuerySpec<SettingStore.Snapshot> result = mock(JdbcClient.MappedQuerySpec.class);
    private final JdbcClient.MappedQuerySpec<Boolean> existsResult = mock(JdbcClient.MappedQuerySpec.class);
    private final AdministrationPort guard = mock(AdministrationPort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final SettingStore store = new SettingStore(jdbc, guard, audit);

    @BeforeEach
    void connectStatement() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(SettingStore.Snapshot.class)).thenReturn(result);
        when(statement.query(Boolean.class)).thenReturn(existsResult);
        when(existsResult.single()).thenReturn(true);
    }

    @Test
    void insertsOnlyWhenTheExpectedVersionIsZero() {
        var saved = new SettingStore.Snapshot(UUID.randomUUID(), 1, "{}");
        when(result.optional()).thenReturn(Optional.of(saved));

        assertSame(saved, store.save("watermark", "{}", "admin", 0, "Enable watermark"));
        verify(jdbc).sql(argThat(sql -> sql.contains("INSERT INTO platform_setting")
                && sql.contains("ON CONFLICT(section) DO NOTHING")));
        verify(audit).append("admin", "setting.update", saved.id());
    }

    @Test
    void updatesOnlyTheVersionTheEditorRead() {
        var saved = new SettingStore.Snapshot(UUID.randomUUID(), 6, "{}");
        when(result.optional()).thenReturn(Optional.of(saved));

        assertSame(saved, store.save("watermark", "{}", "admin", 5, "Update watermark"));
        verify(jdbc).sql(argThat(sql -> sql.contains("UPDATE platform_setting")
                && sql.contains("WHERE section=:section AND version=:expectedVersion")));
        verify(statement, times(2)).param("expectedVersion", 5L);
    }

    @Test
    void routineSettingsUseAnExplicitSystemRecordWhenNoReasonIsSubmitted() {
        var saved = new SettingStore.Snapshot(UUID.randomUUID(), 1, "{}");
        when(result.optional()).thenReturn(Optional.of(saved));

        store.save("watermark", "{}", "admin", 0, null);

        verify(statement).param("reason", SettingStore.SYSTEM_SAVE_REASON);
    }

    @Test
    void securityAndPasskeySettingsStillRequireAnExplicitReason() {
        for (String section : java.util.List.of("security", "passkey")) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> store.save(section, "{}", "admin", 0, "  "));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
            assertEquals("invalidChangeReason", error.getReason());
        }
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    void routineRestoresRecordTheirSourceRevisionWithoutAskingForText() {
        var saved = new SettingStore.Snapshot(UUID.randomUUID(), 2, "{}");
        when(result.optional()).thenReturn(Optional.of(saved));

        store.save("watermark", "{}", "admin", 1, null, 1L);

        verify(statement).param("reason", SettingStore.SYSTEM_RESTORE_REASON_PREFIX + "1.");
    }

    @Test
    void reportsStaleWritesWithoutAppendingAnAuditEntry() {
        when(result.optional()).thenReturn(Optional.empty());

        var error = assertThrows(ResponseStatusException.class,
                () -> store.save("watermark", "{}", "admin", 4, "Stale update"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("staleRecord", error.getReason());
        verify(audit, never()).append(anyString(), anyString(), org.mockito.ArgumentMatchers.any(UUID.class));
    }
}
