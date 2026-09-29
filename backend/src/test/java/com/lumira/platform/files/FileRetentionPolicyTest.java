package com.lumira.platform.files;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.settings.SettingStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileRetentionPolicyTest {
    @Test void missingSettingUsesAutomaticThirtyDayDefault() {
        var store = mock(SettingStore.class);
        when(store.find("file-retention")).thenReturn(Optional.empty());
        var policy = new FileRetentionPolicy(store, mock(FileRepository.class), mock(AuditPort.class), mock(com.lumira.platform.common.AdministrationPort.class), new tools.jackson.databind.ObjectMapper());
        assertEquals(new FileRetentionPolicy.View(0, new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.AUTOMATIC, 30)), policy.read());
    }

    @Test void automaticPolicyEnqueuesExpiredFilesAndManualPolicyDoesNot() {
        var store = mock(SettingStore.class);var files = mock(FileRepository.class);var audit = mock(AuditPort.class);var id = UUID.randomUUID();
        when(files.expireTrashed(14)).thenReturn(List.of(id));
        var policy = new FileRetentionPolicy(store, files, audit, mock(com.lumira.platform.common.AdministrationPort.class), new tools.jackson.databind.ObjectMapper());
        assertEquals(1, policy.applyAutomaticRetention(new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.AUTOMATIC, 14), "system:file-cleanup"));
        assertEquals(0, policy.applyAutomaticRetention(new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.MANUAL, 14), "system:file-cleanup"));
        verify(files).expireTrashed(14);
        verify(audit).append("system:file-cleanup", "file.retentionExpired", id);
    }

    @Test void retentionDaysMustStayWithinOneAnd365() {
        new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.AUTOMATIC, 1).validate();
        new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.MANUAL, 365).validate();
        assertThrows(ResponseStatusException.class, () -> new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.AUTOMATIC, 0).validate());
        assertThrows(ResponseStatusException.class, () -> new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.MANUAL, 366).validate());
        assertThrows(ResponseStatusException.class, () -> new FileRetentionPolicy.Data(null, 30).validate());
    }

    @Test void savingAutomaticPolicyImmediatelyAppliesItToExistingTrash() {
        var store = mock(SettingStore.class);var files = mock(FileRepository.class);var id = UUID.randomUUID();
        when(store.save(eq("file-retention"), anyString(), eq("admin"), eq(0L), isNull())).thenReturn(new SettingStore.Snapshot(UUID.randomUUID(), 1, "{}"));
        when(files.expireTrashed(1)).thenReturn(List.of(id));
        var audit = mock(AuditPort.class);var policy = new FileRetentionPolicy(store, files, audit, mock(com.lumira.platform.common.AdministrationPort.class), new tools.jackson.databind.ObjectMapper());
        var result = policy.save(new FileRetentionPolicy.Input(0, new FileRetentionPolicy.Data(FileRetentionPolicy.Mode.AUTOMATIC, 1), null), "admin");
        assertEquals(1, result.version());
        assertEquals(1, result.data().retentionDays());
        verify(files).expireTrashed(1);
        verify(audit).append("admin", "file.retentionExpired", id);
    }
}
