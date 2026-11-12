package com.lumira.plugins.fileconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.FileQuotaSettings;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class FileQuotaSettingsControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsVersionedSettingsAndReturnsTheSavedSnapshot() throws Exception {
        var settings = mock(FileQuotaSettingsService.class);
        var actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("quota-admin");
        var data = new FileQuotaSettings(12_345, 67);
        var saved = new FileQuotaSettingsService.View(4, data);
        when(settings.save(3, data, "quota-admin")).thenReturn(saved);
        var controller = new FileQuotaSettingsController(settings);

        var result =
                controller.save(
                        mapper.readTree(
                                "{\"version\":3,\"data\":{\"maximumBytes\":12345,\"maximumFiles\":67}}"),
                        actor);

        assertEquals(saved, result);
        verify(settings).save(3, data, "quota-admin");
    }

    @Test
    void acceptsZeroAsUnlimitedForBothDimensions() throws Exception {
        var settings = mock(FileQuotaSettingsService.class);
        var actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("quota-admin");
        var data = new FileQuotaSettings(0, 0);
        var saved = new FileQuotaSettingsService.View(5, data);
        when(settings.save(4, data, "quota-admin")).thenReturn(saved);
        var controller = new FileQuotaSettingsController(settings);

        var result =
                controller.save(
                        mapper.readTree(
                                "{\"version\":4,\"data\":{\"maximumBytes\":0,\"maximumFiles\":0}}"),
                        actor);

        assertEquals(saved, result);
        verify(settings).save(4, data, "quota-admin");
    }

    @Test
    void rejectsNullFractionalNegativeAndUnsafeIntegerValues() throws Exception {
        var settings = mock(FileQuotaSettingsService.class);
        var actor = mock(Authentication.class);
        var controller = new FileQuotaSettingsController(settings);
        List<String> invalidBodies =
                List.of(
                        "null",
                        "{\"version\":0,\"data\":null}",
                        "{\"version\":null,\"data\":{\"maximumBytes\":1,\"maximumFiles\":1}}",
                        "{\"version\":0.5,\"data\":{\"maximumBytes\":1,\"maximumFiles\":1}}",
                        "{\"version\":0,\"data\":{\"maximumBytes\":1.0,\"maximumFiles\":1}}",
                        "{\"version\":0,\"data\":{\"maximumBytes\":-1,\"maximumFiles\":1}}",
                        "{\"version\":0,\"data\":{\"maximumBytes\":1,\"maximumFiles\":-1}}",
                        "{\"version\":0,\"data\":{\"maximumBytes\":9007199254740992,\"maximumFiles\":1}}");

        for (String body : invalidBodies)
            assertThrows(
                    ResponseStatusException.class,
                    () -> controller.save(mapper.readTree(body), actor),
                    body);

        verifyNoInteractions(settings);
    }
}
