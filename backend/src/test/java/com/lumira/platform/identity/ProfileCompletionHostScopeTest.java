package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.identity.api.AvatarStatusPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileCompletionHostScopeTest {
    @Test
    void accountCompletionIgnoresAdminOnlyAndRegistrationOnlyFields() {
        UUID accountId = UUID.randomUUID();
        var accountFields =
                new ProfileFields(
                        List.of(
                                field(
                                        "displayName",
                                        false,
                                        List.of(
                                                new ProfileFields.Placement(
                                                        "account-profile", "basic", 1))),
                                field(
                                        "internalNote",
                                        true,
                                        List.of(
                                                new ProfileFields.Placement(
                                                        "admin-user-editor", "profile", 2))),
                                field(
                                        "publicExtra",
                                        true,
                                        List.of(
                                                new ProfileFields.Placement(
                                                        "public-registration", "additional", 3)))));
        var profiles = mock(ProfileRepository.class);
        when(profiles.find("alice", false))
                .thenReturn(
                        new ProfileRepository.Profile(accountId, "alice", "Alice", "", "", "", 4));
        var values = mock(ProfileValuesService.class);
        when(values.storedValues(accountId)).thenReturn(Map.of());
        var dictionaries = mock(ProfileDictionaryPort.class);
        when(dictionaries.enabledSelections(anyCollection())).thenReturn(Set.of());
        var service =
                new ProfileCompletionService(
                        profiles,
                        values,
                        () -> new ProfileSchemaPort.Snapshot(9, accountFields),
                        dictionaries,
                        mock(AvatarStatusPort.class));

        var score = service.read("alice").score();

        assertEquals(10, score.totalWeight());
        assertEquals(1, score.groups().size());
        assertEquals(
                List.of("displayName"),
                score.groups().getFirst().items().stream()
                        .map(ProfileCompletion.Item::key)
                        .toList());
    }

    private static ProfileFields.Field field(
            String key, boolean custom, List<ProfileFields.Placement> placements) {
        return new ProfileFields.Field(
                key,
                key,
                "",
                "TEXT",
                true,
                false,
                10,
                "",
                null,
                null,
                1,
                custom,
                List.of(),
                null,
                false,
                placements);
    }
}
