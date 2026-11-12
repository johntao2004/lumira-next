package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort.Host;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProfileDictionarySelectionTest {
    private final ProfileSchemaPort schemas = mock(ProfileSchemaPort.class);
    private final ProfileDictionaryPort dictionaries = mock(ProfileDictionaryPort.class);
    private final ProfileDictionarySelectionService selections =
            new ProfileDictionarySelectionService(schemas, dictionaries);

    @Test
    void publishedFieldResolvesItsOwnDictionaryAndForwardsBoundedSelectionQuery() {
        publish(field("department", "SELECT", true, true, "departments", "account-profile"));
        var query = new PageRequest(2, 10, "sales");
        var page = new PageResult<ProfileDictionaryPort.Item>(List.of(), 0, 2, 10);
        when(dictionaries.lookupItems("departments", query)).thenReturn(page);
        assertSame(page, selections.list(Host.ACCOUNT, "department", query));
        verify(dictionaries).lookupItems("departments", query);
    }

    @Test
    void hiddenUnpublishedBuiltinAndNonSelectionFieldsNeverReachProvider() {
        publish(
                field("hidden", "SELECT", false, true, "secret", "account-profile"),
                field("adminOnly", "SELECT", true, true, "internal", "admin-user-editor"),
                field("plain", "TEXT", true, true, "unrelated", "account-profile"),
                field("inline", "SELECT", true, true, null, "account-profile"),
                field("builtin", "SELECT", true, false, "unrelated", "account-profile"));
        for (var key : List.of("hidden", "adminOnly", "plain", "inline", "builtin", "absent")) {
            var failure =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> selections.list(Host.ACCOUNT, key, new PageRequest(1, 10, "")));
            assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        }
        verifyNoInteractions(dictionaries);
    }

    @Test
    void userEditorCannotResolveFieldsPlacedOnlyOnAccount() {
        publish(field("privateChoice", "SELECT", true, true, "private", "account-profile"));
        assertThrows(
                ResponseStatusException.class,
                () -> selections.find(Host.USER_EDITOR, "privateChoice", UUID.randomUUID()));
        verifyNoInteractions(dictionaries);
    }

    @Test
    void everyLookupUsesCurrentPublishedBindingWithoutCachingOldDictionary() {
        var id = UUID.randomUUID();
        var item = new ProfileDictionaryPort.Item(id, null, "sales", "Sales", 1);
        publish(field("department", "SELECT", true, true, "old_departments", "admin-user-editor"));
        when(dictionaries.lookupItem("old_departments", id)).thenReturn(item);
        assertSame(item, selections.find(Host.USER_EDITOR, "department", id));
        publish(field("department", "SELECT", true, true, "new_departments", "admin-user-editor"));
        selections.find(Host.USER_EDITOR, "department", id);
        publish(field("department", "SELECT", false, true, "new_departments", "admin-user-editor"));
        assertThrows(
                ResponseStatusException.class,
                () -> selections.find(Host.USER_EDITOR, "department", id));
        verify(dictionaries, times(1)).lookupItem("new_departments", id);
        verify(dictionaries, times(1)).lookupItem("old_departments", id);
    }

    private void publish(ProfileFields.Field... fields) {
        when(schemas.read())
                .thenReturn(new ProfileSchemaPort.Snapshot(7, new ProfileFields(List.of(fields))));
    }

    private static ProfileFields.Field field(
            String key,
            String type,
            boolean visible,
            boolean custom,
            String dictionary,
            String host) {
        return new ProfileFields.Field(
                key,
                key,
                "",
                type,
                visible,
                false,
                1,
                "",
                null,
                null,
                10,
                custom,
                List.of(),
                dictionary,
                false,
                List.of(
                        new ProfileFields.Placement(
                                host, "account-profile".equals(host) ? "custom" : "profile", 10)));
    }
}
