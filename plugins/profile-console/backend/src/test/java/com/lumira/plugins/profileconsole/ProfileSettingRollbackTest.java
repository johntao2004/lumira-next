package com.lumira.plugins.profileconsole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.settings.SettingStore;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ProfileSettingRollbackTest {
    private final SettingStore store = mock(SettingStore.class);
    private final AdministrationPort guard = mock(AdministrationPort.class);
    private final ProfileDictionaryPort dictionaries = mock(ProfileDictionaryPort.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfileSettingRollbackService rollback =
            new ProfileSettingRollbackService(store, mapper, guard, dictionaries);

    private ProfileFields revision(long count) {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.add(
                new ProfileFields.Field(
                        "qaPublic",
                        "QA public",
                        "",
                        "SELECT",
                        true,
                        false,
                        5,
                        "",
                        "custom",
                        "Custom",
                        100,
                        true,
                        List.of(),
                        "qa_public",
                        true,
                        List.of(
                                new ProfileFields.Placement(
                                        "public-registration", "additional", 100))));
        var data = new ProfileFields(fields).withResolvedPlacements();
        when(store.revision("profile-fields", 2))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Revision(
                                        2,
                                        mapper.writeValueAsString(data),
                                        "author",
                                        "Publication",
                                        OffsetDateTime.now(),
                                        null)));
        when(dictionaries.isEnabled("qa_public")).thenReturn(true);
        when(dictionaries.enabledOptionCounts(Set.of("qa_public")))
                .thenReturn(Map.of("qa_public", count));
        return data;
    }

    @Test
    void rollbackCannotPublishAHistoricalDictionaryThatHasOutgrownTheRegistrationLimit() {
        revision(201);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                rollback.rollback(
                                        new ProfileSettingRollbackService.Input(5, 2, "Restore"),
                                        "admin"));
        assertEquals(400, error.getStatusCode().value());
        assertEquals("registrationDictionaryTooLarge", error.getReason());
        verify(guard).requireOperationGlobal("admin", PlatformOperation.PROFILE_FIELDS_UPDATE);
        verify(store).revision("profile-fields", 2);
        verifyNoMoreInteractions(store);
    }

    @Test
    void rollbackRevalidatesAndPreservesSourceHistoryAtTheCurrentOptionBoundary() {
        var data = revision(200);
        when(store.save(
                        eq("profile-fields"),
                        anyString(),
                        eq("admin"),
                        eq(5L),
                        eq("Restore"),
                        eq(2L)))
                .thenReturn(
                        new SettingStore.Snapshot(
                                UUID.randomUUID(), 6, mapper.writeValueAsString(data)));
        var view =
                rollback.rollback(
                        new ProfileSettingRollbackService.Input(5, 2, "Restore"), "admin");
        assertEquals(6, view.version());
        assertEquals(data, view.data());
        verify(dictionaries).enabledOptionCounts(Set.of("qa_public"));
    }

    @Test
    void rollbackDoesNotRestoreAReferenceToADisabledDictionary() {
        revision(10);
        when(dictionaries.isEnabled("qa_public")).thenReturn(false);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                rollback.rollback(
                                        new ProfileSettingRollbackService.Input(5, 2, "Restore"),
                                        "admin"));
        assertEquals(400, error.getStatusCode().value());
        verify(store).revision("profile-fields", 2);
        verifyNoMoreInteractions(store);
        verify(dictionaries, never()).enabledOptionCounts(any());
    }

    @Test
    void historicalUnusedCredentialPropertyRemainsAcceptedWithoutRetainingIt() {
        var input =
                mapper.readValue(
                        "{\"version\":5,\"sourceVersion\":2,\"changeReason\":\"Restore\",\"credentials\":{\"action\":\"REPLACE\",\"value\":\"synthetic-channel-value\"}}",
                        ProfileSettingRollbackService.Input.class);
        assertEquals(new ProfileSettingRollbackService.Input(5, 2, "Restore"), input);
        assertFalse(input.toString().contains("synthetic-channel-value"));
    }
}
