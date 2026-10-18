package com.lumira.platform.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow, authorization-enforcing user projection used by the directory template feature. */
public interface TemplateUserDataPort {
    record Field(
            String key,
            String label,
            String type,
            String source,
            boolean readable,
            boolean writable) {}

    List<Field> readableFields(String actor);

    List<Field> importableFields(String actor);

    Map<String, String> readValues(UUID userId, String actor);

    default Map<UUID, Map<String, String>> readValuesBatch(List<UUID> userIds, String actor) {
        var values = new java.util.LinkedHashMap<UUID, Map<String, String>>();
        for (UUID userId : userIds) values.put(userId, readValues(userId, actor));
        return Map.copyOf(values);
    }

    default Map<UUID, Map<String, String>> readValuesBatch(
            List<UUID> userIds, String actor, java.util.Set<String> requestedFields) {
        var values = new java.util.LinkedHashMap<UUID, Map<String, String>>();
        for (UUID userId : userIds) {
            var userValues = readValues(userId, actor);
            var filtered = new java.util.LinkedHashMap<String, String>();
            for (String key : requestedFields)
                if (userValues.containsKey(key)) filtered.put(key, userValues.get(key));
            values.put(userId, Map.copyOf(filtered));
        }
        return Map.copyOf(values);
    }
}
