package com.lumira.platform.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AiModelInputPolicy {
    static final List<String> DOCUMENTS =
            List.of("txt", "md", "markdown", "doc", "docx", "pdf", "csv", "json", "log");
    static final List<String> IMAGES = List.of("png", "jpg", "jpeg");
    private static final Set<String> FLASH_VISION =
            Set.of("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp");

    static List<String> defaults(
            AiProviderProfile.ProviderKind kind, String model, boolean imageMetadata) {
        if (kind == AiProviderProfile.ProviderKind.TYPESAFE_JEV) return List.of();
        var result = new ArrayList<>(DOCUMENTS);
        if (imageMetadata
                || kind == AiProviderProfile.ProviderKind.DEEPSEEK && FLASH_VISION.contains(model))
            result.addAll(IMAGES);
        return List.copyOf(result);
    }

    static Map<String, List<String>> normalize(
            Map<String, List<String>> requested, List<String> models) {
        var result = new LinkedHashMap<String, List<String>>();
        if (requested == null) return result;
        if (requested.size() > models.size()) invalid();
        for (var entry : requested.entrySet()) {
            var formats = entry.getValue();
            if (!models.contains(entry.getKey())
                    || formats == null
                    || formats.size() > DOCUMENTS.size() + IMAGES.size()) invalid();
            if (formats.stream()
                            .anyMatch(
                                    format ->
                                            format == null
                                                    || !DOCUMENTS.contains(format)
                                                            && !IMAGES.contains(format))
                    || formats.stream().distinct().count() != formats.size()) invalid();
            result.put(entry.getKey(), List.copyOf(formats));
        }
        return Map.copyOf(result);
    }

    static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    static boolean vision(List<String> formats) {
        return formats.stream().anyMatch(IMAGES::contains);
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidAiProvider");
    }
}
