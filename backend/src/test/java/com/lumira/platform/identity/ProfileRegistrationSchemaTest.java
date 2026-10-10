package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class ProfileRegistrationSchemaTest {
    @Test
    void registrationSchemaProjectsOnlyAllowedOptionalFieldsAndBatchesDictionaries() {
        var fields =
                List.of(
                        publicField(
                                "publicStatic",
                                true,
                                "public-registration",
                                null,
                                List.of("One", "Two")),
                        publicField(
                                "publicDictionary",
                                true,
                                "public-registration",
                                "districts",
                                List.of()),
                        publicField(
                                "privateField",
                                false,
                                "public-registration",
                                null,
                                List.of("Secret")),
                        publicField("otherHost", true, "account-profile", null, List.of("No")),
                        hiddenRegistrationField());
        ProfileSchemaPort schemas =
                () -> new ProfileSchemaPort.Snapshot(17, new ProfileFields(fields));
        var requestedCodes = new ArrayList<Set<String>>();
        var dictionaries = mock(ProfileDictionaryPort.class);
        when(dictionaries.enabledOptionCounts(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of("districts", 1L));
        when(dictionaries.enabledOptions(
                        org.mockito.ArgumentMatchers.anyCollection(),
                        org.mockito.ArgumentMatchers.eq(201)))
                .thenAnswer(
                        invocation -> {
                            Collection<String> codes = invocation.getArgument(0);
                            requestedCodes.add(Set.copyOf(codes));
                            return Map.of(
                                    "districts",
                                    List.of(
                                            new ProfileDictionaryPort.PublicOption(
                                                    "item-id", "Current district")));
                        });
        var service =
                new ProfileValuesService(
                        mock(JdbcClient.class),
                        mock(ProfileRepository.class),
                        schemas,
                        new ObjectMapper(),
                        mock(AuditPort.class),
                        dictionaries);

        var projected = service.registrationSchema();

        assertEquals(17, projected.schemaVersion());
        assertEquals(
                List.of("publicDictionary", "publicStatic"),
                projected.fields().stream().map(field -> field.key()).toList());
        assertTrue(projected.fields().stream().noneMatch(field -> field.required()));
        assertEquals(List.of(Set.of("districts")), requestedCodes);
        assertEquals(
                List.of(
                        new com.lumira.platform.identity.api.ProfileRegistrationPort.PublicOption(
                                "One", "One"),
                        new com.lumira.platform.identity.api.ProfileRegistrationPort.PublicOption(
                                "Two", "Two")),
                projected.fields().get(1).options());
        assertEquals(
                List.of(
                        new com.lumira.platform.identity.api.ProfileRegistrationPort.PublicOption(
                                "item-id", "Current district")),
                projected.fields().getFirst().options());

        var json = new ObjectMapper().valueToTree(projected);
        assertEquals(17, json.path("schemaVersion").intValue());
        var field = json.path("fields").get(0);
        assertFalse(field.path("required").booleanValue());
        assertTrue(field.path("options").get(0).has("value"));
        assertFalse(field.has("dictionaryCode"));
        assertFalse(field.has("publicRegistrationAllowed"));
        assertFalse(field.has("placements"));
    }

    private static ProfileFields.Field publicField(
            String key, boolean allowed, String page, String dictionaryCode, List<String> options) {
        var placements =
                page.equals("public-registration")
                        ? List.of(
                                new ProfileFields.Placement(
                                        "public-registration", "additional", 10),
                                new ProfileFields.Placement("account-profile", "custom", 10))
                        : List.of(new ProfileFields.Placement("account-profile", "custom", 10));
        return new ProfileFields.Field(
                key,
                key,
                "Description",
                "SELECT",
                true,
                true,
                5,
                "Choose",
                "custom",
                "Custom",
                10,
                true,
                options,
                dictionaryCode,
                allowed,
                placements);
    }

    private static ProfileFields.Field hiddenRegistrationField() {
        return new ProfileFields.Field(
                "hiddenRegistration",
                "Hidden",
                "",
                "TEXT",
                false,
                false,
                5,
                "",
                "custom",
                "Custom",
                10,
                true,
                List.of(),
                null,
                true,
                List.of(new ProfileFields.Placement("public-registration", "additional", 20)));
    }
}
