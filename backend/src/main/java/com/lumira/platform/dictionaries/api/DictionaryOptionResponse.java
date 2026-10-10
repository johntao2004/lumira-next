package com.lumira.platform.dictionaries.api;

import com.lumira.platform.dictionaries.DictionaryLookupRepository;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record DictionaryOptionResponse(
        UUID id, UUID parentId, String value, String label, int sortOrder) {
    public static DictionaryOptionResponse from(DictionaryLookupRepository.Option value) {
        if (value == null) return null;
        return new DictionaryOptionResponse(
                value.id(), value.parentId(), value.value(), value.label(), value.sortOrder());
    }
}
