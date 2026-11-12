package com.lumira.plugins.dictionaries.api;

import com.lumira.plugins.dictionaries.DictionaryItemRepository;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record DictionaryItemResponse(
        UUID id,
        UUID dictionaryId,
        UUID parentId,
        String value,
        String label,
        int sortOrder,
        boolean enabled,
        String remark,
        long version) {
    public static DictionaryItemResponse from(DictionaryItemRepository.Item value) {
        if (value == null) return null;
        return new DictionaryItemResponse(
                value.id(),
                value.dictionaryId(),
                value.parentId(),
                value.value(),
                value.label(),
                value.sortOrder(),
                value.enabled(),
                value.remark(),
                value.version());
    }
}
