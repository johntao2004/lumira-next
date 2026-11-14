package com.lumira.platform.common;

import java.util.List;
import java.util.Map;

/** Host-facing client for template inspection, PDF generation, and XLSX exchange. */
public final class DocumentWorkerClient {
    public record Slot(String key, String label, String text) {}

    public record Inspection(List<Slot> slots, List<String> placeholders) {
        public Inspection {
            slots = List.copyOf(slots);
            placeholders = List.copyOf(placeholders);
        }
    }

    private DocumentWorkerClient() {}

    public static Inspection inspect(String filename, byte[] bytes) {
        var result = OfficeWorkerClient.inspectDocument(filename, bytes);
        if (result.slots() == null
                || result.placeholders() == null
                || result.slots().size() > OfficeWorkerProtocol.MAX_SLOTS
                || result.placeholders().size() > OfficeWorkerProtocol.MAX_PLACEHOLDERS) {
            throw OfficeWorkerClient.workerUnavailable();
        }
        return new Inspection(
                result.slots().stream()
                        .map(slot -> new Slot(slot.key(), slot.label(), slot.text()))
                        .toList(),
                result.placeholders());
    }

    public static byte[] pdf(
            String filename,
            byte[] bytes,
            Map<String, String> bindings,
            Map<String, String> values) {
        return OfficeWorkerClient.documentPdf(filename, bytes, bindings, values);
    }

    public static List<List<String>> readWorkbook(byte[] bytes) {
        return OfficeWorkerClient.readWorkbook(bytes).rows();
    }

    public static byte[] writeWorkbook(List<List<String>> rows) {
        return OfficeWorkerClient.writeWorkbook(rows);
    }
}
