package com.lumira.platform.identity.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded shape shared by profile-settings validation and the public registration projection. */
public final class ProfileRegistrationLimits {
    public static final int MAX_FIELDS = 20;
    public static final int MAX_OPTIONS_PER_FIELD = 200;
    public static final int MAX_TOTAL_OPTIONS = 1000;
    public static final int OPTION_QUERY_LIMIT = MAX_OPTIONS_PER_FIELD + 1;
    public static final int MAX_SCHEMA_BYTES = 1024 * 1024;

    private ProfileRegistrationLimits() {}

    public static List<ProfileFields.Field> publicFields(ProfileFields schema) {
        if (schema == null || schema.fields() == null) return List.of();
        return schema.fields().stream()
                .filter(
                        field ->
                                field != null
                                        && Boolean.TRUE.equals(field.custom())
                                        && Boolean.TRUE.equals(field.visible())
                                        && Boolean.TRUE.equals(field.publicRegistrationAllowed())
                                        && field.isPlacedAt("public-registration", "additional"))
                .toList();
    }

    public static Set<String> dictionaryCodes(List<ProfileFields.Field> fields) {
        return fields.stream()
                .filter(ProfileFields.Field::usesDictionary)
                .map(ProfileFields.Field::dictionaryCode)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Counts each field's option slot, even when several fields reuse the same dictionary. */
    public static boolean withinOptionLimits(
            List<ProfileFields.Field> fields, Map<String, Long> enabledOptionCounts) {
        if (fields == null || enabledOptionCounts == null || fields.size() > MAX_FIELDS)
            return false;
        long total = 0;
        for (var field : fields) {
            long count =
                    field.usesDictionary()
                            ? enabledOptionCounts.getOrDefault(field.dictionaryCode(), -1L)
                            : field.options() == null ? -1 : field.options().size();
            if (count < 0 || count > MAX_OPTIONS_PER_FIELD) return false;
            total += count;
            if (total > MAX_TOTAL_OPTIONS) return false;
        }
        return true;
    }
}
