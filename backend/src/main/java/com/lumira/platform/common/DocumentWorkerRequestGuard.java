package com.lumira.platform.common;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Bounds the small JSON metadata frame before worker request model binding. */
public final class DocumentWorkerRequestGuard {
    private static final JsonFactory JSON = JsonFactory.builder().build();
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;

    private DocumentWorkerRequestGuard() {}

    public static void validate(byte[] bytes, String operation) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_METADATA_BYTES)
            throw new IOException("Document request metadata exceeds its limit");
        if (OfficeWorkerProtocol.DOCUMENT_PDF.equals(operation)) validatePdf(bytes);
        else if (OfficeWorkerProtocol.WORKBOOK_WRITE.equals(operation)) validateWorkbook(bytes);
        else throw new IOException("Document operation does not accept metadata");
    }

    static void validateValues(
            java.util.Map<String, String> bindings, java.util.Map<String, String> values)
            throws IOException {
        if (bindings == null
                || values == null
                || bindings.size() > DocumentTemplateProcessor.MAX_SLOTS
                || values.size() > 1_000) throw invalid();
        long characters = 0;
        for (var entry : bindings.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().length() > 256
                    || entry.getValue() == null
                    || entry.getValue().length() > 64) throw invalid();
            characters += entry.getKey().length() + entry.getValue().length();
        }
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().length() > 64
                    || entry.getValue() == null
                    || entry.getValue().length() > DocumentTemplateProcessor.MAX_CELL_CHARACTERS)
                throw invalid();
            characters += entry.getKey().length() + entry.getValue().length();
        }
        if (characters > DocumentTemplateProcessor.MAX_TOTAL_CHARACTERS) throw invalid();
    }

    static void validateRows(java.util.List<java.util.List<String>> rows) throws IOException {
        if (rows == null || rows.size() > DocumentTemplateProcessor.MAX_WORKBOOK_ROWS)
            throw invalid();
        long cells = 0;
        long characters = 0;
        for (java.util.List<String> row : rows) {
            if (row == null || row.size() > DocumentTemplateProcessor.MAX_WORKBOOK_COLUMNS)
                throw invalid();
            cells += row.size();
            if (cells > DocumentTemplateProcessor.MAX_WORKBOOK_CELLS) throw invalid();
            for (String value : row) {
                if (value == null) continue;
                if (value.length() > DocumentTemplateProcessor.MAX_CELL_CHARACTERS) throw invalid();
                characters += value.length();
                if (characters > DocumentTemplateProcessor.MAX_TOTAL_CHARACTERS) throw invalid();
            }
        }
    }

    private static void validatePdf(byte[] bytes) throws IOException {
        var stack = new ArrayDeque<Frame>();
        Set<String> bindingKeys = new HashSet<>();
        Set<String> valueKeys = new HashSet<>();
        boolean rootStarted = false;
        boolean rootEnded = false;
        int tokens = 0;
        long characters = 0;
        try (JsonParser parser = JSON.createParser(bytes)) {
            for (JsonToken token; (token = parser.nextToken()) != null; ) {
                if (++tokens > 10_000) throw invalid();
                if (token == JsonToken.START_OBJECT) {
                    String path = beginObject(stack, rootStarted);
                    rootStarted = true;
                    if (!Set.of("", "bindings", "values").contains(path) || stack.size() >= 3)
                        throw invalid();
                    stack.push(new Frame(path, false));
                    continue;
                }
                if (token == JsonToken.END_OBJECT) {
                    Frame frame = stack.poll();
                    if (frame == null || frame.array) throw invalid();
                    long required = frame.path.isEmpty() ? 0b11L : 0;
                    if (frame.seen != required) throw invalid();
                    if (stack.isEmpty()) rootEnded = true;
                    continue;
                }
                if (token == JsonToken.PROPERTY_NAME) {
                    Frame frame = stack.peek();
                    if (frame == null || frame.array || frame.field != null) throw invalid();
                    String name = parser.getText();
                    if (frame.path.isEmpty()) {
                        int bit =
                                switch (name) {
                                    case "bindings" -> 1;
                                    case "values" -> 2;
                                    default -> -1;
                                };
                        if (bit < 0 || (frame.seen & bit) != 0) throw invalid();
                        frame.seen |= bit;
                    } else {
                        Set<String> keys = "bindings".equals(frame.path) ? bindingKeys : valueKeys;
                        if (name.length() > ("bindings".equals(frame.path) ? 256 : 64)
                                || !keys.add(name)
                                || keys.size()
                                        > ("bindings".equals(frame.path)
                                                ? DocumentTemplateProcessor.MAX_SLOTS
                                                : 1_000)) throw invalid();
                        characters += name.length();
                        if (characters > DocumentTemplateProcessor.MAX_TOTAL_CHARACTERS)
                            throw invalid();
                    }
                    frame.field = name;
                    continue;
                }
                if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT)
                    throw invalid();
                Frame frame = stack.peek();
                if (frame == null || frame.array || frame.path.isEmpty() || frame.field == null)
                    throw invalid();
                if (token != JsonToken.VALUE_STRING) throw invalid();
                String value = parser.getText();
                int maximum =
                        "bindings".equals(frame.path)
                                ? 64
                                : DocumentTemplateProcessor.MAX_CELL_CHARACTERS;
                if (value.length() > maximum) throw invalid();
                characters += value.length();
                if (characters > DocumentTemplateProcessor.MAX_TOTAL_CHARACTERS) throw invalid();
                frame.field = null;
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalid();
        }
        if (!rootStarted || !rootEnded || !stack.isEmpty()) throw invalid();
    }

    private static void validateWorkbook(byte[] bytes) throws IOException {
        var stack = new ArrayDeque<Frame>();
        boolean rootStarted = false;
        boolean rootEnded = false;
        int tokens = 0;
        long cells = 0;
        long characters = 0;
        try (JsonParser parser = JSON.createParser(bytes)) {
            for (JsonToken token; (token = parser.nextToken()) != null; ) {
                if (++tokens > 220_000) throw invalid();
                if (token == JsonToken.START_OBJECT) {
                    String path = beginObject(stack, rootStarted);
                    rootStarted = true;
                    if (!"".equals(path) || stack.size() != 0) throw invalid();
                    stack.push(new Frame(path, false));
                    continue;
                }
                if (token == JsonToken.END_OBJECT) {
                    Frame frame = stack.poll();
                    if (frame == null || frame.array || frame.seen != 1) throw invalid();
                    rootEnded = true;
                    continue;
                }
                if (token == JsonToken.PROPERTY_NAME) {
                    Frame frame = stack.peek();
                    if (frame == null
                            || frame.array
                            || frame.field != null
                            || !"rows".equals(parser.getText())
                            || frame.seen != 0) throw invalid();
                    frame.seen = 1;
                    frame.field = "rows";
                    continue;
                }
                if (token == JsonToken.START_ARRAY) {
                    String path;
                    if (stack.isEmpty()) throw invalid();
                    Frame parent = stack.peek();
                    if (parent.array) {
                        if (parent.path.equals("rows")) {
                            if (++parent.count > DocumentTemplateProcessor.MAX_WORKBOOK_ROWS)
                                throw invalid();
                            path = "row";
                        } else if (parent.path.equals("row")) {
                            if (++parent.count > DocumentTemplateProcessor.MAX_WORKBOOK_COLUMNS)
                                throw invalid();
                            cells++;
                            path = "cell";
                        } else throw invalid();
                    } else {
                        if (!parent.path.isEmpty() || !"rows".equals(parent.field)) throw invalid();
                        parent.field = null;
                        path = "rows";
                    }
                    if (cells > DocumentTemplateProcessor.MAX_WORKBOOK_CELLS || stack.size() >= 4)
                        throw invalid();
                    stack.push(new Frame(path, true));
                    continue;
                }
                if (token == JsonToken.END_ARRAY) {
                    Frame frame = stack.poll();
                    if (frame == null || !frame.array) throw invalid();
                    continue;
                }
                Frame frame = stack.peek();
                if (frame == null
                        || !frame.array
                        || !"row".equals(frame.path)
                        || token != JsonToken.VALUE_STRING) throw invalid();
                if (++frame.count > DocumentTemplateProcessor.MAX_WORKBOOK_COLUMNS) throw invalid();
                String value = parser.getText();
                if (value.length() > DocumentTemplateProcessor.MAX_CELL_CHARACTERS) throw invalid();
                cells++;
                characters += value.length();
                if (cells > DocumentTemplateProcessor.MAX_WORKBOOK_CELLS
                        || characters > DocumentTemplateProcessor.MAX_TOTAL_CHARACTERS)
                    throw invalid();
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalid();
        }
        if (!rootStarted || !rootEnded || !stack.isEmpty()) throw invalid();
    }

    private static IOException invalid() {
        return new IOException("Invalid or oversized document request metadata");
    }

    private static String beginObject(ArrayDeque<Frame> stack, boolean rootStarted)
            throws IOException {
        if (!rootStarted) return "";
        Frame parent = stack.peek();
        if (parent == null || parent.array || parent.field == null) throw invalid();
        String path = parent.path.isEmpty() ? parent.field : parent.path + "." + parent.field;
        parent.field = null;
        return path;
    }

    private static final class Frame {
        final String path;
        final boolean array;
        int count;
        long seen;
        String field;

        Frame(String path, boolean array) {
            this.path = path;
            this.array = array;
        }
    }
}
