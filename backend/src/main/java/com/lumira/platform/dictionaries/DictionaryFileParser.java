package com.lumira.platform.dictionaries;

import com.lumira.platform.common.ImportValidationException;
import com.lumira.platform.common.ImportValidationException.Issue;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Legacy TXT (tab separated), XLS and XLSX adapter into the shared dataset validator. */
@Component
public class DictionaryFileParser {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    static final int MAX_COLUMNS = 64;

    public record Parsed(String sha256, List<DictionaryDataset.Entry> items) {}

    private record SourceRow(int number, List<String> cells) {}

    private record Hierarchy(int row, int level, boolean leaf, String parent) {}

    public Parsed parse(byte[] bytes, String filename, String structure) {
        if (bytes.length > MAX_BYTES)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "dictionaryDatasetTooLarge");
        if (bytes.length == 0
                || filename == null
                || !("TREE".equals(structure) || "FLAT".equals(structure))) throw invalid();
        try {
            String extension =
                    filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            List<SourceRow> rows =
                    switch (extension) {
                        case "txt" -> textRows(bytes);
                        case "xls", "xlsx" -> workbookRows(bytes);
                        default -> throw invalid();
                    };
            if (rows.isEmpty()) throw invalid();
            Map<String, Integer> columns = new HashMap<>();
            var headers = rows.getFirst().cells();
            for (int i = 0; i < headers.size(); i++) {
                String key = headers.get(i).strip().toLowerCase(Locale.ROOT);
                if (!key.isEmpty() && columns.putIfAbsent(key, i) != null) throw invalid();
            }
            if (!columns.containsKey("itemvalue") || !columns.containsKey("itemlabel"))
                throw invalid();
            List<Issue> issues = new ArrayList<>();
            List<DictionaryDataset.Entry> items = new ArrayList<>();
            Map<String, Hierarchy> hierarchy = new HashMap<>();
            for (var row : rows.subList(1, rows.size())) {
                if (row.cells().stream().allMatch(String::isBlank)) continue;
                if (items.size() == DictionaryDataset.MAX_ITEMS)
                    throw new ResponseStatusException(
                            HttpStatus.PAYLOAD_TOO_LARGE, "dictionaryDatasetTooLarge");
                try {
                    String value = cell(row, columns, "itemvalue"),
                            label = cell(row, columns, "itemlabel");
                    if (value.isBlank() || value.length() > 200)
                        throw issue(row.number(), "itemValue", "invalid");
                    if (label.isBlank() || label.length() > 200)
                        throw issue(row.number(), "itemLabel", "invalid");
                    if (cell(row, columns, "remark").length() > 1000)
                        throw issue(row.number(), "remark", "invalid");
                    String parent = cell(row, columns, "parentitemvalue");
                    int level = number(row, columns, "levelno", 1);
                    int sort = number(row, columns, "sortno", row.number() * 10);
                    String status = cell(row, columns, "status").toUpperCase(Locale.ROOT);
                    if (!Set.of("", "ENABLED", "DISABLED").contains(status))
                        throw issue(row.number(), "status", "invalid");
                    boolean leaf;
                    try {
                        leaf = bool(cell(row, columns, "leaf"));
                    } catch (ResponseStatusException ex) {
                        throw issue(row.number(), "leaf", "invalid");
                    }
                    // Flat legacy datasets ignore hierarchy-only columns.
                    if ("FLAT".equals(structure)) {
                        parent = "";
                        level = 1;
                        leaf = true;
                    }
                    if (level < 1 || level > 9) throw issue(row.number(), "levelNo", "invalid");
                    parent = parent.isEmpty() ? null : parent;
                    if (hierarchy.putIfAbsent(
                                    value, new Hierarchy(row.number(), level, leaf, parent))
                            != null) throw issue(row.number(), "itemValue", "duplicate");
                    items.add(
                            new DictionaryDataset.Entry(
                                    value,
                                    label,
                                    parent,
                                    sort,
                                    !"DISABLED".equals(status),
                                    cell(row, columns, "remark")));
                } catch (ImportValidationException ex) {
                    issues.addAll(ex.issues());
                    if (issues.size() >= 100) throw new ImportValidationException(issues);
                }
            }
            for (var row : hierarchy.values()) {
                if (row.level() == 1 && row.parent() != null)
                    issues.add(new Issue(row.row(), "parentItemValue", "hierarchy"));
                if (row.level() > 1) {
                    var parent = hierarchy.get(row.parent());
                    if (parent == null || parent.level() + 1 != row.level() || parent.leaf())
                        issues.add(new Issue(row.row(), "parentItemValue", "hierarchy"));
                }
            }
            if (!issues.isEmpty()) throw new ImportValidationException(issues);
            new DictionaryDataset(1, "file", "file", true, structure, "", items).validatedOrder();
            return new Parsed(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    List.copyOf(items));
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private List<SourceRow> textRows(byte[] bytes) throws IOException {
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<SourceRow> rows = new ArrayList<>();
        try (var reader = new BufferedReader(new StringReader(text))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (number > 1 && line.isBlank()) continue;
                if (rows.size() > DictionaryDataset.MAX_ITEMS)
                    throw new ResponseStatusException(
                            HttpStatus.PAYLOAD_TOO_LARGE, "dictionaryDatasetTooLarge");
                rows.add(new SourceRow(number, Arrays.asList(line.split("\t", -1))));
            }
        }
        return rows;
    }

    private List<SourceRow> workbookRows(byte[] bytes) throws IOException {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw invalid();
            var sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() - sheet.getFirstRowNum() > DictionaryDataset.MAX_ITEMS)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "dictionaryDatasetTooLarge");
            var header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw invalid();
            int columns = header.getLastCellNum();
            // A sparse header can declare thousands of columns; every row would then allocate that
            // many cells.
            if (columns > MAX_COLUMNS) throw invalid();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<SourceRow> rows = new ArrayList<>();
            for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                for (int j = 0; j < columns; j++)
                    cells.add(formatter.formatCellValue(row.getCell(j)));
                rows.add(new SourceRow(i + 1, cells));
            }
            return rows;
        }
    }

    private static String cell(SourceRow row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= row.cells().size() ? "" : row.cells().get(index).strip();
    }

    private static ImportValidationException issue(int row, String field, String reason) {
        return new ImportValidationException(List.of(new Issue(row, field, reason)));
    }

    private static int number(
            SourceRow row, Map<String, Integer> columns, String field, int fallback) {
        try {
            return integer(cell(row, columns, field), fallback);
        } catch (NumberFormatException ex) {
            throw issue(row.number(), field, "invalid");
        }
    }

    private static int integer(String value, int fallback) {
        return value.isEmpty() ? fallback : Integer.parseInt(value.replaceFirst("\\.0+$", ""));
    }

    private static boolean bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "", "1", "true", "yes", "\u662f" -> true;
            case "0", "false", "no", "\u5426" -> false;
            default -> throw invalid();
        };
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionaryDataset");
    }
}
