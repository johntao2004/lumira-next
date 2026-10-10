package com.lumira.platform.identity;

import com.lumira.platform.identity.api.ProfileFields;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Replace visible custom values while preserving hidden/retired values for later schema
 * restoration.
 */
public final class ProfileValues {
    private ProfileValues() {}

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidProfileValues");
    }

    public static Map<String, String> merge(
            ProfileFields schema, Map<String, String> old, Map<String, String> input) {
        var editable = new ArrayList<ProfileFields.Field>();
        for (var field : schema.fields())
            if (field.custom() && field.visible()) editable.add(field);
        return merge(editable, old, input, true);
    }

    public static Map<String, String> mergeForPlacement(
            ProfileFields schema,
            Map<String, String> old,
            Map<String, String> input,
            String page,
            String tab,
            boolean enforceRequired) {
        var editable = new ArrayList<ProfileFields.Field>();
        for (var field : schema.fields())
            if (field.custom()
                    && field.visible()
                    && field.placementsOrLegacy().stream()
                            .anyMatch(
                                    p ->
                                            p.page().equals(page)
                                                    && (tab == null || p.tab().equals(tab))))
                editable.add(field);
        return merge(editable, old, input, enforceRequired);
    }

    public static Map<String, String> merge(
            List<ProfileFields.Field> editableFields,
            Map<String, String> old,
            Map<String, String> input,
            boolean enforceRequired) {
        if (input == null || input.size() > 100) {
            invalid();
            return Map.of();
        }
        long payloadBytes = 0;
        for (var entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                invalid();
                return Map.of();
            }
            payloadBytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            payloadBytes += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes > 64 * 1024) {
                invalid();
                return Map.of();
            }
        }
        var editable = new LinkedHashMap<String, ProfileFields.Field>();
        for (var field : editableFields) editable.put(field.key(), field);
        if (!editable.keySet().containsAll(input.keySet())) invalid();
        var result = new LinkedHashMap<>(old);
        for (var entry : editable.entrySet()) {
            var field = entry.getValue();
            if (!input.containsKey(entry.getKey())) {
                if (enforceRequired && field.required()) invalid();
                continue;
            }
            String raw = input.get(entry.getKey());
            if (raw == null) {
                invalid();
                return Map.of();
            }
            int maxLength = field.type().equals("TEXTAREA") ? 2000 : 200;
            String value = raw.strip();
            if (value.length() > maxLength
                    || value.getBytes(StandardCharsets.UTF_8).length > maxLength * 4) invalid();
            if (value.isEmpty()) {
                if (enforceRequired && field.required()) invalid();
                result.remove(entry.getKey());
                continue;
            }
            try {
                switch (field.type()) {
                    case "NUMBER" -> {
                        if (!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) invalid();
                        var number = new BigDecimal(value);
                        if (number.precision() > 30 || number.scale() > 10) invalid();
                    }
                    case "DATE" -> {
                        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) invalid();
                        LocalDate.parse(value);
                    }
                    case "SELECT" -> {
                        if (field.usesDictionary()) {
                            UUID.fromString(value);
                        } else if (!field.options().contains(value)) invalid();
                    }
                    default -> {}
                }
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                invalid();
            }
            result.put(entry.getKey(), value);
        }
        return Map.copyOf(result);
    }

    public static Map<String, String> visible(ProfileFields schema, Map<String, String> stored) {
        var result = new LinkedHashMap<String, String>();
        for (var f : schema.fields())
            if (f.custom() && f.visible() && stored.containsKey(f.key()))
                result.put(f.key(), stored.get(f.key()));
        return Map.copyOf(result);
    }

    public static Map<String, String> visibleForPlacement(
            ProfileFields schema, Map<String, String> stored, String page, String tab) {
        var result = new LinkedHashMap<String, String>();
        for (var f : schema.fields())
            if (f.custom()
                    && f.visible()
                    && f.placementsOrLegacy().stream()
                            .anyMatch(
                                    p ->
                                            p.page().equals(page)
                                                    && (tab == null || p.tab().equals(tab)))
                    && stored.containsKey(f.key())) result.put(f.key(), stored.get(f.key()));
        return Map.copyOf(result);
    }
}
