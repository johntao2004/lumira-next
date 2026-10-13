package com.lumira.platform.identity.api;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Identity's narrow contract for validating dictionary-backed profile values. */
public interface ProfileDictionaryPort {
    /** Enabled dictionary metadata for the profile-field editor, bounded by the shared page. */
    default PageResult<EnabledDictionary> listEnabled(PageRequest page) {
        throw new UnsupportedOperationException("Enabled dictionary listing is unavailable");
    }

    record EnabledDictionary(String code, String name, boolean enabled) {}

    /** Current enabled option counts for several allow-listed dictionaries in one lookup. */
    default Map<String, Long> enabledOptionCounts(Collection<String> codes) {
        return Map.of();
    }

    /** Resolve bounded enabled options for several allow-listed dictionaries in one lookup. */
    default Map<String, java.util.List<PublicOption>> enabledOptions(
            Collection<String> codes, int perDictionaryLimit) {
        return Map.of();
    }

    /** Resolve whether a dictionary is enabled without exposing its management metadata. */
    default boolean isEnabled(String code) {
        return false;
    }

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

    /** Public field option shape; never contains dictionary remarks or management metadata. */
    record PublicOption(String value, String label) {}
}
