package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.FileRetentionSettings;
import com.lumira.platform.settings.SettingStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FileRetentionReaderTest {
    @Test
    void missingPolicyKeepsTheStorageDefault() {
        var store = mock(SettingStore.class);
        when(store.find("file-retention")).thenReturn(Optional.empty());
        assertEquals(
                FileRetentionSettings.defaults(),
                new FileRetentionReader(store, new ObjectMapper()).current());
    }

    @Test
    void persistedManualPolicyIsReadWithoutTheAdministrationPlugin() {
        var store = mock(SettingStore.class);
        when(store.find("file-retention"))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Snapshot(
                                        UUID.randomUUID(),
                                        7,
                                        "{\"mode\":\"MANUAL\",\"retentionDays\":90}")));
        assertEquals(
                new FileRetentionSettings(FileRetentionSettings.Mode.MANUAL, 90),
                new FileRetentionReader(store, new ObjectMapper()).current());
    }
}
