package com.lumira.platform.users;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Compatibility for export pages opened before display labels were added to the request. */
@Component
public final class UserXlsxExportLocalization {
    private final UserRepository users;
    private final Map<String, Map<String, String>> dictionaries;

    public UserXlsxExportLocalization(UserRepository users, ObjectMapper mapper) {
        this.users = users;
        // Generated from the same messages.ts dictionaries used by the web application.
        try (var input = new ClassPathResource("builtin-messages.json").getInputStream()) {
            var root = mapper.readTree(input);
            dictionaries =
                    mapper.readValue(root.path("locales").toString(), new TypeReference<>() {});
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load export display translations", error);
        }
    }

    Map<String, String> messages(String actor) {
        String locale = users.preferredLocale(actor);
        return dictionaries.getOrDefault(
                locale == null ? "zh-CN" : locale, dictionaries.get("zh-CN"));
    }

    Map<String, String> fieldLabels(
            List<String> selected,
            Map<String, UserXlsxExportService.Field> catalog,
            Map<String, String> messages) {
        var labels = new LinkedHashMap<String, String>();
        for (String key : selected) {
            var field = catalog.get(key);
            String messageKey =
                    switch (key) {
                        case "username" -> "username";
                        case "displayName" -> "name";
                        case "enabled" -> "status";
                        case "roles" -> "userFieldRoles";
                        case "organizations" -> "userFieldOrganizations";
                        default -> profileMessageKey(field);
                    };
            labels.put(
                    key,
                    messageKey == null
                            ? field.label()
                            : messages.getOrDefault(messageKey, field.label()));
        }
        return labels;
    }

    Map<String, String> statusLabels(Map<String, String> messages) {
        return Map.of("true", messages.get("enabled"), "false", messages.get("disabled"));
    }

    private static String profileMessageKey(UserXlsxExportService.Field field) {
        if ("custom".equals(field.source())) return null;
        // Match the web profile-field localization contract; preserve administrator renames.
        return switch (field.key()) {
            case "realName" -> "Real name".equals(field.label()) ? "realName" : null;
            case "birthDate" -> "Birth date".equals(field.label()) ? "birthMonth" : null;
            case "region" -> "Region".equals(field.label()) ? "region" : null;
            case "avatarUrl" -> "Avatar".equals(field.label()) ? "avatar" : null;
            default -> null;
        };
    }
}
