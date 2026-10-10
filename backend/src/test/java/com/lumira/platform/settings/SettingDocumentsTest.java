package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SettingDocumentsTest {
    private final SettingStore store = mock(SettingStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingDocuments documents = new SettingDocuments(store, mapper);

    @Test
    void readsPersistedVersionAndUsesDefaultsOnlyForMissingDocuments() {
        var defaults = WatermarkSettings.defaults();
        when(store.find("watermark"))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Snapshot(
                                        UUID.randomUUID(),
                                        7,
                                        mapper.writeValueAsString(defaults))));
        var result =
                documents.read(
                        "watermark",
                        WatermarkSettings.class,
                        () -> {
                            throw new AssertionError("Stored document must not use defaults");
                        });
        assertEquals(7, result.version());
        assertEquals(defaults, result.data());
        when(store.find("watermark")).thenReturn(Optional.empty());
        assertEquals(
                new SettingDocuments.Snapshot<>(0, defaults),
                documents.read("watermark", WatermarkSettings.class, () -> defaults));
    }

    @Test
    void validatesBeforePersistenceAndKeepsTheSectionErrorCode() {
        var missing =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                documents.save(
                                        "watermark",
                                        3,
                                        null,
                                        "Reason",
                                        "admin",
                                        "invalidWatermark",
                                        WatermarkSettings::validate));
        assertEquals("invalidWatermark", missing.getReason());
        var invalid = new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidWatermark");
        assertSame(
                invalid,
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                documents.save(
                                        "watermark",
                                        3,
                                        WatermarkSettings.defaults(),
                                        "Reason",
                                        "admin",
                                        "invalidWatermark",
                                        ignored -> {
                                            throw invalid;
                                        })));
        verifyNoInteractions(store);
    }

    @Test
    void preservesConcurrencyActorReasonAndTheVersionAssignedByTheStore() {
        var data = WatermarkSettings.defaults();
        String payload = mapper.writeValueAsString(data);
        when(store.save("watermark", payload, "admin", 3, "Reason"))
                .thenReturn(new SettingStore.Snapshot(UUID.randomUUID(), 4, payload));
        assertEquals(
                new SettingDocuments.Snapshot<>(4, data),
                documents.save(
                        "watermark",
                        3,
                        data,
                        "Reason",
                        "admin",
                        "invalidWatermark",
                        WatermarkSettings::validate));
        var conflict = new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        when(store.save("watermark", payload, "admin", 3, "Reason")).thenThrow(conflict);
        assertSame(
                conflict,
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                documents.save(
                                        "watermark",
                                        3,
                                        data,
                                        "Reason",
                                        "admin",
                                        "invalidWatermark",
                                        WatermarkSettings::validate)));
    }
}
