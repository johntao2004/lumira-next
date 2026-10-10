package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ProfileServiceHostProjectionTest {
    @Test
    void profileViewStripsPlacementsForOtherHosts() {
        var accountPlacement = new ProfileFields.Placement("account-profile", "basic", 10);
        var editorPlacement = new ProfileFields.Placement("admin-user-editor", "profile", 20);
        var schema =
                new ProfileSchemaPort.Snapshot(
                        5,
                        new ProfileFields(
                                List.of(
                                        new ProfileFields.Field(
                                                "displayName",
                                                "Display name",
                                                "",
                                                "TEXT",
                                                true,
                                                true,
                                                1,
                                                "",
                                                null,
                                                null,
                                                10,
                                                false,
                                                List.of(),
                                                null,
                                                true,
                                                List.of(accountPlacement, editorPlacement)))));
        var account =
                new ProfileRepository.Profile(UUID.randomUUID(), "alice", "Alice", "", "", "", 1);
        var repository = mock(ProfileRepository.class);
        org.mockito.Mockito.when(repository.find("alice", false)).thenReturn(account);
        var service =
                new ProfileService(
                        repository,
                        mock(AuditPort.class),
                        () -> schema,
                        mock(JdbcClient.class),
                        mock(ProfileValuesService.class));

        var view = service.find("alice");

        assertEquals(1, view.fields().size());
        assertEquals(List.of(accountPlacement), view.fields().getFirst().placements());
        assertFalse(view.fields().getFirst().publicRegistrationAllowed());
    }
}
