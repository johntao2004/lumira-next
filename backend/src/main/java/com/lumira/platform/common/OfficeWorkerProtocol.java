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
    public static final String DOCUMENT_INSPECT = "document-inspect";
    public static final String DOCUMENT_PDF = "document-pdf";
    public static final String WORKBOOK_READ = "workbook-read";
    public static final String WORKBOOK_WRITE = "workbook-write";
    public static final String TEST_TIMEOUT = "test-timeout";
    public static final String TEST_OOM = "test-oom";
    public static final String CHILD_RESPONSE_FILE = "result.json";
    public static final String CHILD_METADATA_FILE = "metadata.json";
    public static final String REQUEST_FRAME_MAGIC = "LDO1";
    public static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    public record ChildRequest(
            String operation,
            String filename,
            String structure,
            String inputFile,
            String metadataFile) {}

    public record Preview(String text, boolean truncated) {}

    public record Dictionary(String sha256, List<DictionaryItem> items) {}

    public record DictionaryItem(
            String value,
            String label,
            String parentValue,
            int sortOrder,
            boolean enabled,
            String remark) {}

    public record Slot(String key, String label, String text) {}

    public record Inspection(List<Slot> slots, List<String> placeholders) {}

    public record WorkbookData(List<List<String>> rows) {}

    public record DocumentMetadata(
            java.util.Map<String, String> bindings, java.util.Map<String, String> values) {}

    public record WorkbookWriteMetadata(List<List<String>> rows) {}

    public record Issue(int row, String field, String reason) {}

    public record Response(
            String kind,
            Preview preview,
            Dictionary dictionary,
            Inspection inspection,
            byte[] documentPdf,
            WorkbookData workbook,
            byte[] workbookFile,
            int status,
            String reason,
            List<Issue> issues) {
        public static Response preview(Preview value) {
            return new Response(PREVIEW, value, null, null, null, null, null, 0, null, List.of());
        }

        public static Response dictionary(Dictionary value) {
            return new Response(
                    DICTIONARY, null, value, null, null, null, null, 0, null, List.of());
        }

        public static Response inspection(Inspection value) {
            return new Response(
                    DOCUMENT_INSPECT, null, null, value, null, null, null, 0, null, List.of());
        }

        public static Response documentPdf(byte[] value) {
            return new Response(
                    DOCUMENT_PDF, null, null, null, value, null, null, 0, null, List.of());
        }

        public static Response workbook(WorkbookData value) {
            return new Response(
                    WORKBOOK_READ, null, null, null, null, value, null, 0, null, List.of());
        }

        public static Response workbookFile(byte[] value) {
            return new Response(
                    WORKBOOK_WRITE, null, null, null, null, null, value, 0, null, List.of());
        }

        public static Response error(String kind, int status, String reason, List<Issue> issues) {
            return new Response(
                    kind, null, null, null, null, null, null, status, reason, List.copyOf(issues));
        }
    }

    private OfficeWorkerProtocol() {}
}
