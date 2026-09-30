package com.lumira.platform.identity.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Identity's narrow contract for validating dictionary-backed profile values. */
public interface ProfileDictionaryPort {
    Set<Selection> enabledSelections(Collection<Selection> requested);

    /**
     * Validate selections already stored for the same profile fields. The selected item may be
     * disabled. It must still belong to the current dictionary code and its ancestry must reach a
     * dictionary root. Enablement is deliberately ignored for existing values.
     */
    Set<Selection> existingSelections(Collection<Selection> requested);

    /** Resolve caller-owned stored values without changing enabled-only public lookup semantics. */
    Map<Selection, SelectionMetadata> selectionMetadata(Collection<Selection> requested);

    record Selection(String code, UUID id) {}

    record SelectionMetadata(String label, boolean enabled) {}
}
