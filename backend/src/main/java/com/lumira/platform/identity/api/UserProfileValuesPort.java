package com.lumira.platform.identity.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow contract for the user editor's profile tab; authorization stays in the users module. */
public interface UserProfileValuesPort {
    EditorSnapshot readForEditor(UUID accountId);

    long writeForEditor(
            UUID accountId,
            String actor,
            long expectedVersion,
            long schemaVersion,
            Map<String, String> values);

    record EditorSnapshot(
            long version,
            long schemaVersion,
            List<ProfileFields.Field> fields,
            Map<String, String> values,
            Map<String, DictionarySelection> dictionarySelections) {}

    record DictionarySelection(UUID itemId, String label, boolean enabled) {}
}
