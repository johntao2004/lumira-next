package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ProfileFieldsControllerTest {
    @Test
    void legacyReadSuppliesImageAndSortsWithoutWritingSettings() {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.removeIf(field -> field.key().equals("avatarUrl"));
        java.util.Collections.reverse(fields);
        var store = mock(SettingStore.class);
        var mapper = new ObjectMapper();
        when(store.find("profile-fields"))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Snapshot(
                                        UUID.randomUUID(),
                                        7,
                                        mapper.writeValueAsString(new ProfileFields(fields)))));
        var read = new ProfileSchemaReader(store, mapper).read();
        assertEquals(7, read.version());
        assertEquals(ProfileFields.defaults().withResolvedPlacements().sorted(), read.data());
        verify(store).find("profile-fields");
        org.mockito.Mockito.verifyNoMoreInteractions(store);
    }

    @Test
    void accountSchemaOnlyReturnsVisibleAccountFieldsAndStripsOtherHostMetadata() {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.add(
                customField(
                        "registrationOnly",
                        true,
                        true,
                        new ProfileFields.Placement("public-registration", "additional", 1)));
        fields.add(
                customField(
                        "adminOnly",
                        true,
                        false,
                        new ProfileFields.Placement("admin-user-editor", "profile", 2)));
        fields.add(
                customField(
                        "shared",
                        true,
                        true,
                        new ProfileFields.Placement("account-profile", "details", 3),
                        new ProfileFields.Placement("public-registration", "additional", 4)));
        fields.add(
                customField(
                        "hidden",
                        false,
                        false,
                        new ProfileFields.Placement("account-profile", "custom", 5)));

        var store = mock(SettingStore.class);
        var mapper = new ObjectMapper();
        when(store.find("profile-fields"))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Snapshot(
                                        UUID.randomUUID(),
                                        9,
                                        mapper.writeValueAsString(new ProfileFields(fields)))));
        var controller =
                new ProfileFieldsController(
                        store,
                        mapper,
                        mock(AdministrationPort.class),
                        mock(ProfileDictionaryPort.class),
                        new ProfileSchemaReader(store, mapper));

        var accountSchema = controller.accountSchema();

        assertEquals(9, accountSchema.version());
        assertEquals(ProfileFields.class, accountSchema.data().getClass());
        var projected = accountSchema.data().fields();
        org.junit.jupiter.api.Assertions.assertFalse(
                projected.stream().anyMatch(field -> field.key().equals("registrationOnly")));
        org.junit.jupiter.api.Assertions.assertFalse(
                projected.stream().anyMatch(field -> field.key().equals("adminOnly")));
        org.junit.jupiter.api.Assertions.assertFalse(
                projected.stream().anyMatch(field -> field.key().equals("hidden")));
        var shared =
                projected.stream()
                        .filter(field -> field.key().equals("shared"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                java.util.List.of(new ProfileFields.Placement("account-profile", "details", 3)),
                shared.placements());
        assertEquals(false, shared.publicRegistrationAllowed());
        org.junit.jupiter.api.Assertions.assertTrue(
                projected.stream()
                        .flatMap(field -> field.placements().stream())
                        .allMatch(placement -> placement.page().equals("account-profile")));
        verify(store).find("profile-fields");
        verifyNoMoreInteractions(store);
    }

    @Test
    void customFieldDefinitionSurvivesSaveAndSchemaReload() {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.add(
                new ProfileFields.Field(
                        "profileField_0123456789abcdef0123456789abcdef",
                        "Emergency contact",
                        "Who to contact",
                        "TEXTAREA",
                        true,
                        true,
                        5,
                        "Name and phone",
                        "custom",
                        "Additional information",
                        100,
                        true,
                        java.util.List.of()));
        var data = new ProfileFields(fields);

        var store = mock(SettingStore.class);
        var saved = new AtomicReference<SettingStore.Snapshot>();
        when(store.save(
                        eq("profile-fields"),
                        anyString(),
                        eq("test-admin"),
                        eq(1L),
                        eq("Add emergency contact")))
                .thenAnswer(
                        call -> {
                            var snapshot =
                                    new SettingStore.Snapshot(
                                            UUID.randomUUID(),
                                            2,
                                            call.getArgument(1, String.class));
                            saved.set(snapshot);
                            return snapshot;
                        });
        when(store.find("profile-fields")).thenAnswer(call -> Optional.ofNullable(saved.get()));

        var mapper = new ObjectMapper();
        var schemas = new ProfileSchemaReader(store, mapper);
        var controller =
                new ProfileFieldsController(
                        store,
                        mapper,
                        mock(AdministrationPort.class),
                        mock(ProfileDictionaryPort.class),
                        schemas);
        var authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("test-admin");

        var written =
                controller.write(
                        new ProfileFieldsController.Input(1, data, "Add emergency contact"),
                        authentication);
        var reloaded = controller.read();

        assertEquals(2, written.version());
        assertEquals(2, reloaded.version());
        var normalized = data.withResolvedPlacements().sorted();
        assertEquals(normalized, reloaded.data());
        var persistedJson = ArgumentCaptor.forClass(String.class);
        verify(store)
                .save(
                        eq("profile-fields"),
                        persistedJson.capture(),
                        eq("test-admin"),
                        eq(1L),
                        eq("Add emergency contact"));
        assertEquals(normalized, mapper.readValue(persistedJson.getValue(), ProfileFields.class));
    }

    @Test
    void rejectsPublicOptionTotalsOverOneThousandBeforeSaving() {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        for (int fieldIndex = 0; fieldIndex < 5; fieldIndex++) {
            fields.add(publicSelectField("public" + fieldIndex, 200));
        }
        fields.add(publicSelectField("publicLast", 1));
        var store = mock(SettingStore.class);
        var dictionaries = mock(ProfileDictionaryPort.class);
        var controller =
                new ProfileFieldsController(
                        store,
                        new ObjectMapper(),
                        mock(AdministrationPort.class),
                        dictionaries,
                        new ProfileSchemaReader(store, new ObjectMapper()));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.write(
                                        new ProfileFieldsController.Input(
                                                1, new ProfileFields(fields), "Publish"),
                                        mock(Authentication.class)));

        assertEquals("registrationDictionaryTooLarge", error.getReason());
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void countsRepeatedDictionaryOptionsPerPublicFieldWithOneBatchLookup() {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        for (int fieldIndex = 0; fieldIndex < 6; fieldIndex++)
            fields.add(publicDictionaryField("shared" + fieldIndex));
        var store = mock(SettingStore.class);
        var dictionaries = mock(ProfileDictionaryPort.class);
        when(dictionaries.isEnabled("districts")).thenReturn(true);
        when(dictionaries.enabledOptionCounts(java.util.Set.of("districts")))
                .thenReturn(java.util.Map.of("districts", 200L));
        var controller =
                new ProfileFieldsController(
                        store,
                        new ObjectMapper(),
                        mock(AdministrationPort.class),
                        dictionaries,
                        new ProfileSchemaReader(store, new ObjectMapper()));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.write(
                                        new ProfileFieldsController.Input(
                                                1, new ProfileFields(fields), "Publish"),
                                        mock(Authentication.class)));

        assertEquals("registrationDictionaryTooLarge", error.getReason());
        verify(dictionaries).enabledOptionCounts(java.util.Set.of("districts"));
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    private static ProfileFields.Field publicSelectField(String key, int count) {
        var options = new ArrayList<String>();
        for (int index = 0; index < count; index++) options.add(key + "-option-" + index);
        return new ProfileFields.Field(
                key,
                key,
                "",
                "SELECT",
                true,
                false,
                5,
                "",
                "custom",
                "Custom",
                10,
                true,
                options,
                null,
                true,
                java.util.List.of(
                        new ProfileFields.Placement("public-registration", "additional", 10)));
    }

    private static ProfileFields.Field publicDictionaryField(String key) {
        return new ProfileFields.Field(
                key,
                key,
                "",
                "SELECT",
                true,
                false,
                5,
                "",
                "custom",
                "Custom",
                10,
                true,
                java.util.List.of(),
                "districts",
                true,
                java.util.List.of(
                        new ProfileFields.Placement("public-registration", "additional", 10)));
    }

    private static ProfileFields.Field customField(
            String key,
            boolean visible,
            boolean publicRegistrationAllowed,
            ProfileFields.Placement... placements) {
        return new ProfileFields.Field(
                key,
                key,
                "",
                "TEXT",
                visible,
                false,
                5,
                "",
                "custom",
                "Custom",
                10,
                true,
                java.util.List.of(),
                null,
                publicRegistrationAllowed,
                java.util.List.of(placements));
    }
}
