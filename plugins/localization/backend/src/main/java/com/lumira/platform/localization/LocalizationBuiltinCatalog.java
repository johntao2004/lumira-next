package com.lumira.platform.localization;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Build-time projection of the web app's static dictionaries. messages.ts remains the source of
 * truth.
 */
@Component
public final class LocalizationBuiltinCatalog {
    private final String sourceLocale;
    private final Map<String, Map<String, String>> locales;
    private final Set<String> keys;

    public LocalizationBuiltinCatalog(ObjectMapper mapper) {
        try (var input = new ClassPathResource("builtin-messages.json").getInputStream()) {
            JsonNode root = mapper.readTree(input);
            if (!root.path("formatVersion").canConvertToInt()
                    || root.path("formatVersion").asInt() != 1) {
                throw new IllegalStateException(
                        "Unsupported generated localization catalog format");
            }
            this.sourceLocale = root.path("sourceLocale").asText("");
            if (!"en".equals(sourceLocale))
                throw new IllegalStateException(
                        "Generated localization source locale must be English");
            var type = new TypeReference<LinkedHashMap<String, LinkedHashMap<String, String>>>() {};
            var parsed = mapper.readValue(root.path("locales").toString(), type);
            var copy = new LinkedHashMap<String, Map<String, String>>();
            parsed.forEach(
                    (locale, messages) ->
                            copy.put(
                                    locale,
                                    Collections.unmodifiableMap(new LinkedHashMap<>(messages))));
            var english = copy.get(sourceLocale);
            if (english == null
                    || english.isEmpty()
                    || root.path("keyCount").asInt(-1) != english.size()) {
                throw new IllegalStateException(
                        "Generated localization catalog key count is invalid");
            }
            for (var locale : copy.entrySet()) {
                if (!english.keySet().equals(locale.getValue().keySet())) {
                    throw new IllegalStateException(
                            "Generated locale key set differs from the English catalog: "
                                    + locale.getKey());
                }
            }
            this.locales = Collections.unmodifiableMap(copy);
            this.keys = Set.copyOf(english.keySet());
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Run the backend Maven generate-resources phase to create builtin-messages.json",
                    error);
        }
    }

    public String sourceLocale() {
        return sourceLocale;
    }

    public Set<String> keys() {
        return keys;
    }

    public boolean hasKey(String key) {
        return keys.contains(key);
    }

    public String sourceText(String key) {
        return locales.get(sourceLocale).get(key);
    }

    public Map<String, String> messages(String locale) {
        return locales.getOrDefault(locale, Map.of());
    }

    public Set<String> locales() {
        return locales.keySet();
    }
}
