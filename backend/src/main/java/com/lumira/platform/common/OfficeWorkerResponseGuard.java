package com.lumira.platform.common;

import java.util.ArrayDeque;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Rejects oversized or structurally unexpected worker JSON before model deserialization. */
final class OfficeWorkerResponseGuard {
    private static final JsonFactory JSON = JsonFactory.builder().build();
    private static final int MAX_TOKENS = 350_000;

    private OfficeWorkerResponseGuard() {}

    static void validate(byte[] bytes, String operation) throws Exception {
        var frames = new ArrayDeque<Frame>();
        boolean rootStarted = false;
        boolean rootEnded = false;
        int tokens = 0;
        int workbookCells = 0;
        try (JsonParser parser = JSON.createParser(bytes)) {
            for (JsonToken token; (token = parser.nextToken()) != null; ) {
                if (++tokens > MAX_TOKENS) throw invalid("Office response has too many fields");
                if (token == JsonToken.START_OBJECT) {
                    String path;
                    if (!rootStarted) {
                        rootStarted = true;
                        path = "";
                    } else {
                        if (rootEnded) throw invalid("Trailing JSON value");
                        Frame parent = frames.peek();
                        if (parent == null) throw invalid("Invalid response nesting");
                        if (parent.array) {
                            parent.count++;
                            if (parent.count > arrayLimit(parent.path))
                                throw invalid("Office response array exceeds its bound");
                            path = parent.path + "[]";
                        } else {
                            path = valuePath(parent);
                            parent.field = null;
                        }
                    }
                    if (!objectPath(path) || frames.size() >= 5)
                        throw invalid("Invalid Office response object");
                    frames.push(new Frame(path, false));
                    continue;
                }
                if (token == JsonToken.END_OBJECT) {
                    Frame frame = frames.poll();
                    if (frame == null || frame.array || !completeObject(frame.path, frame.seen))
                        throw invalid("Incomplete Office response object");
                    if (frames.isEmpty()) rootEnded = true;
                    continue;
                }
                if (token == JsonToken.PROPERTY_NAME) {
                    Frame frame = frames.peek();
                    if (frame == null || frame.array || parser.getTextLength() > 32)
                        throw invalid("Invalid Office response property");
                    String name = parser.getText();
                    int index = fieldIndex(frame.path, name);
                    if (index < 0 || (frame.seen & (1L << index)) != 0)
                        throw invalid("Unexpected Office response property");
                    frame.seen |= 1L << index;
                    frame.field = name;
                    continue;
                }
                if (token == JsonToken.START_ARRAY) {
                    Frame parent = frames.peek();
                    if (parent == null) throw invalid("Invalid Office response array");
                    String path;
                    if (parent.array) {
                        parent.count++;
                        if (parent.count > arrayLimit(parent.path))
                            throw invalid("Office response array exceeds its bound");
                        path = parent.path + "[]";
                    } else {
                        path = valuePath(parent);
                        parent.field = null;
                    }
                    if (!arrayPath(path)) throw invalid("Unexpected Office response array");
                    frames.push(new Frame(path, true));
                    continue;
                }
                if (token == JsonToken.END_ARRAY) {
                    Frame frame = frames.poll();
                    if (frame == null || !frame.array)
                        throw invalid("Invalid Office response array");
                    continue;
                }
                if (rootEnded) throw invalid("Trailing JSON value");
                Frame parent = frames.peek();
                if (parent == null) throw invalid("Invalid response value");
                String path;
                if (parent.array) {
                    parent.count++;
                    if (parent.count > arrayLimit(parent.path))
                        throw invalid("Office response array exceeds its bound");
                    path = parent.path + "[]";
                } else {
                    path = valuePath(parent);
                    parent.field = null;
                }
                validateScalar(parser, token, path);
                if ("workbook.rows[][]".equals(path)
                        && ++workbookCells > DocumentTemplateProcessor.MAX_WORKBOOK_CELLS)
                    throw invalid("Workbook response has too many cells");
                if ("kind".equals(path) && !operation.equals(parser.getText()))
                    throw invalid("Office response operation mismatch");
            }
        }
        if (!rootStarted || !rootEnded || !frames.isEmpty())
            throw invalid("Incomplete Office response JSON");
    }

    private static void validateScalar(JsonParser parser, JsonToken token, String path)
            throws Exception {
        if (token == JsonToken.VALUE_STRING) {
            int maximum =
                    switch (path) {
                        case "kind" -> 32;
                        case "reason", "issues[].field", "issues[].reason" -> 128;
                        case "preview.text" -> OfficeDocumentProcessor.MAX_PREVIEW_CODE_POINTS * 2;
                        case "dictionary.sha256" -> 64;
                        case "dictionary.items[].value",
                                "dictionary.items[].label",
                                "dictionary.items[].parentValue" ->
                                200;
                        case "dictionary.items[].remark" -> 1000;
                        case "inspection.slots[].key" -> 256;
                        case "inspection.slots[].label" -> 200;
                        case "inspection.slots[].text" -> 1000;
                        case "inspection.placeholders[]" -> 64;
                        case "documentPdf" ->
                                (DocumentTemplateProcessor.MAX_RESPONSE_PDF_BYTES * 4 / 3) + 8;
                        case "workbookFile" -> (20 * 1024 * 1024 * 4 / 3) + 8;
                        case "workbook.rows[][]" -> DocumentTemplateProcessor.MAX_CELL_CHARACTERS;
                        default -> throw invalid("Unexpected Office response text");
                    };
            if (parser.getTextLength() > maximum)
                throw invalid("Office response text exceeds its bound");
            return;
        }
        if (token == JsonToken.VALUE_NULL) {
            if (!nullablePath(path)) throw invalid("Unexpected null in Office response");
            return;
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            if (!integerPath(path) || parser.getTextLength() > 11)
                throw invalid("Invalid Office response number");
            return;
        }
        if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
            if (!booleanPath(path)) throw invalid("Invalid Office response flag");
            return;
        }
        throw invalid("Unsupported Office response value");
    }

    private static String valuePath(Frame parent) {
        if (parent.field == null) throw invalid("Missing Office response field");
        return parent.path.isEmpty() ? parent.field : parent.path + "." + parent.field;
    }

    private static boolean objectPath(String path) {
        return List.of(
                        "",
                        "preview",
                        "dictionary",
                        "dictionary.items[]",
                        "issues[]",
                        "inspection",
                        "inspection.slots[]",
                        "workbook")
                .contains(path);
    }

    private static boolean arrayPath(String path) {
        return List.of(
                        "dictionary.items",
                        "issues",
                        "inspection.slots",
                        "inspection.placeholders",
                        "workbook.rows",
                        "workbook.rows[]")
                .contains(path);
    }

    private static int arrayLimit(String path) {
        return switch (path) {
            case "dictionary.items" -> 20_000;
            case "issues" -> 100;
            case "inspection.slots", "inspection.placeholders" ->
                    DocumentTemplateProcessor.MAX_SLOTS;
            case "workbook.rows" -> DocumentTemplateProcessor.MAX_WORKBOOK_ROWS;
            case "workbook.rows[]" -> DocumentTemplateProcessor.MAX_WORKBOOK_COLUMNS;
            default -> 0;
        };
    }

    private static int fieldIndex(String path, String field) {
        return switch (path) {
            case "" ->
                    switch (field) {
                        case "kind" -> 0;
                        case "preview" -> 1;
                        case "dictionary" -> 2;
                        case "inspection" -> 3;
                        case "documentPdf" -> 4;
                        case "workbook" -> 5;
                        case "workbookFile" -> 6;
                        case "status" -> 7;
                        case "reason" -> 8;
                        case "issues" -> 9;
                        default -> -1;
                    };
            case "preview" ->
                    switch (field) {
                        case "text" -> 0;
                        case "truncated" -> 1;
                        default -> -1;
                    };
            case "dictionary" ->
                    switch (field) {
                        case "sha256" -> 0;
                        case "items" -> 1;
                        default -> -1;
                    };
            case "dictionary.items[]" ->
                    switch (field) {
                        case "value" -> 0;
                        case "label" -> 1;
                        case "parentValue" -> 2;
                        case "sortOrder" -> 3;
                        case "enabled" -> 4;
                        case "remark" -> 5;
                        default -> -1;
                    };
            case "issues[]" ->
                    switch (field) {
                        case "row" -> 0;
                        case "field" -> 1;
                        case "reason" -> 2;
                        default -> -1;
                    };
            case "inspection" ->
                    switch (field) {
                        case "slots" -> 0;
                        case "placeholders" -> 1;
                        default -> -1;
                    };
            case "inspection.slots[]" ->
                    switch (field) {
                        case "key" -> 0;
                        case "label" -> 1;
                        case "text" -> 2;
                        default -> -1;
                    };
            case "workbook" -> "rows".equals(field) ? 0 : -1;
            default -> -1;
        };
    }

    private static boolean nullablePath(String path) {
        return List.of(
                        "preview",
                        "dictionary",
                        "inspection",
                        "documentPdf",
                        "workbook",
                        "workbookFile",
                        "reason",
                        "dictionary.items[].parentValue")
                .contains(path);
    }

    private static boolean integerPath(String path) {
        return "status".equals(path)
                || "dictionary.items[].sortOrder".equals(path)
                || "issues[].row".equals(path);
    }

    private static boolean booleanPath(String path) {
        return "preview.truncated".equals(path) || "dictionary.items[].enabled".equals(path);
    }

    private static long requiredFields(String path) {
        return switch (path) {
            case "" -> 0b11_1000_0111L;
            case "preview", "dictionary", "inspection" -> 0b11L;
            case "dictionary.items[]" -> 0b11_1111L;
            case "issues[]" -> 0b111L;
            case "inspection.slots[]" -> 0b111L;
            case "workbook" -> 1L;
            default -> -1L;
        };
    }

    private static boolean completeObject(String path, long seen) {
        long required = requiredFields(path);
        return path.isEmpty() ? (seen & required) == required : seen == required;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static final class Frame {
        final String path;
        final boolean array;
        long seen;
        int count;
        String field;

        Frame(String path, boolean array) {
            this.path = path;
            this.array = array;
        }
    }
}
