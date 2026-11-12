package com.lumira.plugins.dictionaries.api;

import com.lumira.plugins.dictionaries.DictionaryRepository;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record DictionaryResponse(
        UUID id,
        String code,
        String name,
        boolean enabled,
        String structure,
        String remark,
        long version) {
    public static DictionaryResponse from(DictionaryRepository.Dictionary value) {
        if (value == null) return null;
        return new DictionaryResponse(
                value.id(),
                value.code(),
                value.name(),
                value.enabled(),
                value.structure(),
                value.remark(),
                value.version());
    }
}
