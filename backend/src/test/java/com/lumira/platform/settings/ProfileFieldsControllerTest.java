package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;

class ProfileFieldsControllerTest {
    @Test
    void customFieldDefinitionSurvivesSaveAndSchemaReload() {
        var fields = new ArrayList<>(ProfileFields.defaults().fields());
        fields.add(new ProfileFields.Field(
                "profileField_0123456789abcdef0123456789abcdef",
                "Emergency contact", "Who to contact", "TEXTAREA", true, true,
                5, "Name and phone", "custom", "Additional information", 100,
                true, java.util.List.of()));
        var data = new ProfileFields(fields);

        var store = mock(SettingStore.class);
        var saved = new AtomicReference<SettingStore.Snapshot>();
        when(store.save(eq("profile-fields"), anyString(), eq("test-admin"), eq(1L), eq("Add emergency contact")))
                .thenAnswer(call -> {
                    var snapshot = new SettingStore.Snapshot(
                            UUID.randomUUID(), 2, call.getArgument(1, String.class));
                    saved.set(snapshot);
                    return snapshot;
                });
        when(store.find("profile-fields")).thenAnswer(call -> Optional.ofNullable(saved.get()));

        var mapper = new ObjectMapper();
        var schemas = new ProfileSchemaReader(store, mapper);
        var controller = new ProfileFieldsController(
                store, mapper, mock(AdministrationPort.class), mock(DictionaryLookupPort.class), schemas);
        var authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("test-admin");

        var written = controller.write(
                new ProfileFieldsController.Input(1, data, "Add emergency contact"), authentication);
        var reloaded = controller.read();

        assertEquals(2, written.version());
        assertEquals(2, reloaded.version());
        assertEquals(data, reloaded.data());
        var persistedJson = ArgumentCaptor.forClass(String.class);
        verify(store).save(eq("profile-fields"), persistedJson.capture(), eq("test-admin"),
                eq(1L), eq("Add emergency contact"));
        assertEquals(data, mapper.readValue(persistedJson.getValue(), ProfileFields.class));
    }
}
