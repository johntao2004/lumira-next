package com.lumira.platform.identity.api;

import java.util.*;

/**
 * Versioned identity profile schema. Keys are stable identifiers, never labels
 * or executable expressions.
 */
public record ProfileFields(List<Field> fields) {
    /** Domain validation failure; HTTP adapters decide how to expose it. */
    public static final class Invalid extends IllegalArgumentException {
        public Invalid() {
            super("invalidProfileFields");
        }
    }

    public record Field(
            String key,
            String label,
            String description,
            String type,
            Boolean visible,
            Boolean required,
            Integer weight,
            String placeholder,
            String groupKey,
            String groupLabel,
            Integer sortNo,
            Boolean custom,
            List<String> options,
            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String dictionaryCode) {
        public Field(
                String key,
                String label,
                String description,
                String type,
                Boolean visible,
                Boolean required,
                Integer weight,
                String placeholder,
                String groupKey,
                String groupLabel,
                Integer sortNo,
                Boolean custom,
                List<String> options) {
            this(key, label, description, type, visible, required, weight, placeholder,
                    groupKey, groupLabel, sortNo, custom, options, null);
        }

        public boolean usesDictionary() {
            return dictionaryCode != null && !dictionaryCode.isEmpty();
        }
    }

    private static final Map<String, String> BUILT_INS = Map.of(
            "displayName", "TEXT",
            "realName", "TEXT",
            "birthDate", "DATE",
            "region", "TEXT",
            "avatarUrl", "IMAGE");
    private static final Set<String> RESERVED = Set.of(
            "id", "userId", "username", "password", "passwordHash", "authorities",
            "globalAuthorities", "version", "enabled", "email", "mobile", "nickname",
            "__proto__", "prototype", "constructor");

    public Optional<Field> avatar() {
        return fields.stream().filter(f -> f.key().equals("avatarUrl") && !f.custom()).findFirst();
    }

    public static ProfileFields defaults() {
        return new ProfileFields(List.of(
                builtIn("displayName", "Display name", "TEXT", true, 10),
                builtIn("realName", "Real name", "TEXT", false, 20),
                builtIn("birthDate", "Birth date", "DATE", false, 30),
                builtIn("region", "Region", "TEXT", false, 40)));
    }

    private static Field builtIn(String key, String label, String type, boolean required, int sort) {
        return new Field(key, label, "", type, true, required, 5, "", "basic",
                "Basic information", sort, false, List.of());
    }

    private static boolean text(String value, int max) {
        return value != null && value.length() <= max;
    }

    private static void invalid() {
        throw new Invalid();
    }

    public void validate() {
        if (fields == null || fields.size() > 100) {
            invalid();
            return;
        }
        Set<String> keys = new HashSet<>();
        Map<String, String> groups = new HashMap<>();
        for (Field field : fields) {
            if (field == null
                    || field.key() == null
                    || !field.key().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !keys.add(field.key())
                    || RESERVED.contains(field.key())
                    || !text(field.label(), 64)
                    || field.label().isBlank()
                    || !text(field.description(), 200)
                    || !text(field.placeholder(), 120)
                    || field.type() == null
                    || !Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "IMAGE").contains(field.type())
                    || field.visible() == null
                    || field.required() == null
                    || field.custom() == null
                    || field.weight() == null
                    || field.weight() < 1
                    || field.weight() > 100
                    || field.sortNo() == null
                    || field.sortNo() < 0
                    || field.sortNo() > 100000
                    || field.groupKey() == null
                    || !field.groupKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !text(field.groupLabel(), 64)
                    || field.groupLabel().isBlank()
                    || field.options() == null
                    || field.options().size() > 200) {
                invalid();
                return;
            }
            String group = groups.putIfAbsent(field.groupKey(), field.groupLabel());
            if (group != null && !group.equals(field.groupLabel())) {
                invalid();
            }
            if (BUILT_INS.containsKey(field.key())) {
                if (field.custom() || !BUILT_INS.get(field.key()).equals(field.type())) invalid();
            } else if (!field.custom() || field.type().equals("IMAGE")) {
                invalid();
            }
            if (field.key().equals("displayName") && (!field.visible() || !field.required())) invalid();
            if (field.usesDictionary()) {
                if (!field.custom()
                        || !field.type().equals("SELECT")
                        || !field.dictionaryCode().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")
                        || !field.options().isEmpty()) invalid();
            } else if (field.type().equals("SELECT")) {
                if (field.options().isEmpty()) invalid();
                Set<String> options = new HashSet<>();
                for (String option : field.options()) {
                    if (!text(option, 200) || option.isBlank() || !option.equals(option.strip())
                            || !options.add(option)) invalid();
                }
            } else if (!field.options().isEmpty()) {
                invalid();
            }
        }
        if (!keys.containsAll(Set.of("displayName", "realName", "birthDate", "region"))) invalid();
    }
}
