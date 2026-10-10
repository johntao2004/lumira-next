package com.lumira.platform.common;

import java.util.List;

/** Bounded request and response values shared by the host and isolated Office worker. */
public final class OfficeWorkerProtocol {
    public static final String TOKEN_HEADER = "X-Lumira-Office-Token";
    public static final String OPERATION_HEADER = "X-Lumira-Office-Operation";
    public static final String FILENAME_HEADER = "X-Lumira-Office-Filename";
    public static final String STRUCTURE_HEADER = "X-Lumira-Office-Structure";
    public static final String PREVIEW = "preview";
    public static final String DICTIONARY = "dictionary";
    public static final String TEST_TIMEOUT = "test-timeout";
    public static final String TEST_OOM = "test-oom";
    public static final String CHILD_RESPONSE_FILE = "result.json";
    public static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    public record ChildRequest(
            String operation, String filename, String structure, String inputFile) {}

    public record Preview(String text, boolean truncated) {}

    public record Dictionary(String sha256, List<DictionaryItem> items) {}

    public record DictionaryItem(
            String value,
            String label,
            String parentValue,
            int sortOrder,
            boolean enabled,
            String remark) {}

    public record Issue(int row, String field, String reason) {}

    public record Response(
            String kind,
            Preview preview,
            Dictionary dictionary,
            int status,
            String reason,
            List<Issue> issues) {
        public static Response preview(Preview value) {
            return new Response(PREVIEW, value, null, 0, null, List.of());
        }

        public static Response dictionary(Dictionary value) {
            return new Response(DICTIONARY, null, value, 0, null, List.of());
        }

        public static Response error(String kind, int status, String reason, List<Issue> issues) {
            return new Response(kind, null, null, status, reason, List.copyOf(issues));
        }
    }

    private OfficeWorkerProtocol() {}
}
