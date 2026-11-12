package com.lumira.plugins.fileretention;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.FileRetentionQueuePort;
import com.lumira.platform.common.FileRetentionSettings;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.settings.SettingStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FileRetentionAdministrationTest {
    @Test
    void missingSettingUsesAutomaticThirtyDayDefault() {
        var store = mock(SettingStore.class);
        var guard = mock(AdministrationPort.class);
        when(store.find("file-retention")).thenReturn(Optional.empty());
        var policy =
                new FileRetentionAdministration(
                        store,
                        mock(FileRetentionQueuePort.class),
                        mock(AuditPort.class),
                        guard,
                        new tools.jackson.databind.ObjectMapper());
        assertEquals(
                new FileRetentionAdministration.View(
                        0, new FileRetentionSettings(FileRetentionSettings.Mode.AUTOMATIC, 30)),
                policy.read("admin"));
        verify(guard).requirePageGlobal("admin", PlatformPageId.FILE_RETENTION);
        verify(guard, never())
                .requireOperationGlobal("admin", PlatformOperation.FILE_RETENTION_UPDATE);
    }

    @Test
    void automaticPolicyEnqueuesExpiredFilesAndManualPolicyDoesNot() {
        var store = mock(SettingStore.class);
        var files = mock(FileRetentionQueuePort.class);
        var audit = mock(AuditPort.class);
        var id = UUID.randomUUID();
        when(files.expireTrashed(14)).thenReturn(List.of(id));
        var policy =
                new FileRetentionAdministration(
                        store,
                        files,
                        audit,
                        mock(com.lumira.platform.common.AdministrationPort.class),
                        new tools.jackson.databind.ObjectMapper());
        assertEquals(
                1,
                policy.applyAutomaticRetention(
                        new FileRetentionSettings(FileRetentionSettings.Mode.AUTOMATIC, 14),
                        "system:file-cleanup"));
        assertEquals(
                0,
                policy.applyAutomaticRetention(
                        new FileRetentionSettings(FileRetentionSettings.Mode.MANUAL, 14),
                        "system:file-cleanup"));
        verify(files).expireTrashed(14);
        verify(audit).append("system:file-cleanup", "file.retentionExpired", id);
    }

    @Test
    void retentionDaysMustStayWithinOneAnd365() {
        new FileRetentionSettings(FileRetentionSettings.Mode.AUTOMATIC, 1).validate();
        new FileRetentionSettings(FileRetentionSettings.Mode.MANUAL, 365).validate();
        assertThrows(
                ResponseStatusException.class,
                () ->
                        new FileRetentionSettings(FileRetentionSettings.Mode.AUTOMATIC, 0)
                                .validate());
        assertThrows(
                ResponseStatusException.class,
                () -> new FileRetentionSettings(FileRetentionSettings.Mode.MANUAL, 366).validate());
        assertThrows(
                ResponseStatusException.class,
                () -> new FileRetentionSettings(null, 30).validate());
    }

    @Test
    void savingAutomaticPolicyImmediatelyAppliesItToExistingTrash() {
        var store = mock(SettingStore.class);
        var files = mock(FileRetentionQueuePort.class);
        var id = UUID.randomUUID();
        when(store.save(eq("file-retention"), anyString(), eq("admin"), eq(0L), isNull()))
                .thenReturn(new SettingStore.Snapshot(UUID.randomUUID(), 1, "{}"));
        when(files.expireTrashed(1)).thenReturn(List.of(id));
        var audit = mock(AuditPort.class);
        var guard = mock(AdministrationPort.class);
        var policy =
                new FileRetentionAdministration(
                        store, files, audit, guard, new tools.jackson.databind.ObjectMapper());
        var result =
                policy.save(
                        new FileRetentionAdministration.Input(
                                0,
                                new FileRetentionSettings(FileRetentionSettings.Mode.AUTOMATIC, 1),
                                null),
                        "admin");
        assertEquals(1, result.version());
        assertEquals(1, result.data().retentionDays());
        verify(files).expireTrashed(1);
        verify(audit).append("admin", "file.retentionExpired", id);
        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_RETENTION_UPDATE);
    }

    @Test
    void retentionSaveRequiresUpdateOperationBeforeValidatingOrWriting() {
        var store = mock(SettingStore.class);
        var guard = mock(AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.FILE_RETENTION_UPDATE);
        var policy =
                new FileRetentionAdministration(
                        store,
                        mock(FileRetentionQueuePort.class),
                        mock(AuditPort.class),
                        guard,
                        new tools.jackson.databind.ObjectMapper());

        var error = assertThrows(ResponseStatusException.class, () -> policy.save(null, "admin"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(store, never()).save(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void retentionRollbackRequiresUpdateOperationBeforeReadingRevision() {
        var store = mock(SettingStore.class);
        var guard = mock(AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.FILE_RETENTION_UPDATE);
        var policy =
                new FileRetentionAdministration(
                        store,
                        mock(FileRetentionQueuePort.class),
                        mock(AuditPort.class),
                        guard,
                        new tools.jackson.databind.ObjectMapper());

        assertThrows(ResponseStatusException.class, () -> policy.rollback(null, "admin"));

        verify(store, never()).revision(anyString(), anyLong());
        verify(store, never()).save(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void largeBacklogCannotMakeASaveLoopAcrossTheWholeTrash() {
        var store = mock(SettingStore.class);
        var files = mock(FileRetentionQueuePort.class);
        var batch =
                java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
        // A second batch fails immediately, so a regression cannot hang the suite.
        when(files.expireTrashed(14))
                .thenReturn(batch)
                .thenThrow(new AssertionError("Retention save exceeded one bounded batch"));
        var policy =
                new FileRetentionAdministration(
                        store,
                        files,
                        mock(AuditPort.class),
                        mock(AdministrationPort.class),
                        new tools.jackson.databind.ObjectMapper());
        assertEquals(
                100,
                policy.applyAutomaticRetention(
                        new FileRetentionSettings(FileRetentionSettings.Mode.AUTOMATIC, 14),
                        "admin"));
        verify(files, times(1)).expireTrashed(14);
    }
}
