package com.lumira.platform.identity.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow identity contract for optional, explicitly published registration profile fields. */
public interface ProfileRegistrationPort {
    RegistrationSchema registrationSchema();

    /** Must join the caller's account-creation transaction and persist values for that account. */
    void validateAndStoreByAccount(UUID accountId, long schemaVersion, Map<String, String> values);

    record RegistrationSchema(long schemaVersion, List<PublicField> fields) {}

    record PublicField(
            String key,
            String label,
            String description,
            String type,
            boolean required,
            String placeholder,
            List<PublicOption> options) {}

    record PublicOption(String value, String label) {}
}
