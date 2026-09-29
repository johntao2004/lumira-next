package com.lumira.platform.identity.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Identity's narrow contract for validating dictionary-backed profile values. */
public interface ProfileDictionaryPort {
    Set<Selection> enabledSelections(Collection<Selection> requested);

    record Selection(String code, UUID id) {}
}
