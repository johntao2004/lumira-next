package com.lumira.platform.feedback;

import com.lumira.platform.content.RichText;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Input boundary shared by feedback creation and future imported-content adapters. */
public record FeedbackContent(String title, String detailHtml) {
    public static final int MAX_TITLE = 160, MAX_DETAIL = 200_000, MAX_DETAIL_BYTES = 800_000;
    public static final String IMAGE_URL_PREFIX = "/api/feedback/images/";
    private static final Pattern IMAGE_TAG =
            Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_ATTRIBUTE =
            Pattern.compile("\\bsrc\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    public static FeedbackContent validate(String title, String detailHtml) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isBlank()
                || normalized.length() > MAX_TITLE
                || normalized.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)
                || detailHtml == null
                || detailHtml.length() > MAX_DETAIL
                || detailHtml.getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES)
            throw invalid();
        var clean = RichText.clean(detailHtml);
        if (!clean.meaningful()
                || clean.html().length() > MAX_DETAIL
                || clean.html().getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES)
            throw invalid();
        imageIds(clean.html());
        return new FeedbackContent(normalized, clean.html());
    }

    /** Extract only normalized application images; external and data URLs are rejected. */
    public static Set<UUID> imageIds(String html) {
        var ids = new LinkedHashSet<UUID>();
        Matcher tags = IMAGE_TAG.matcher(html == null ? "" : html);
        while (tags.find()) {
            Matcher src = SRC_ATTRIBUTE.matcher(tags.group());
            if (!src.find()) throw invalid();
            String value = src.group(1);
            if (!value.startsWith(IMAGE_URL_PREFIX)
                    || value.length() != IMAGE_URL_PREFIX.length() + 36) throw invalid();
            try {
                ids.add(UUID.fromString(value.substring(IMAGE_URL_PREFIX.length())));
            } catch (IllegalArgumentException error) {
                throw invalid();
            }
        }
        return Set.copyOf(ids);
    }

    public static String reply(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > 4000 || normalized.indexOf(0) >= 0) throw invalid();
        return normalized.isEmpty() ? null : normalized;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFeedback");
    }
}
