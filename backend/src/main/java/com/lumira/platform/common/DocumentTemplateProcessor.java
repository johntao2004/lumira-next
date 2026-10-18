package com.lumira.platform.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/** Template inspection, workbook exchange, and conversion code used only by the isolated worker. */
public final class DocumentTemplateProcessor {
    public static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    public static final int MAX_RESPONSE_PDF_BYTES = 44 * 1024 * 1024;
    public static final int MAX_SLOTS = 500;
    public static final int MAX_PLACEHOLDERS = 500;
    public static final int MAX_WORKBOOK_ROWS = 5_000;
    public static final int MAX_WORKBOOK_COLUMNS = 256;
    public static final int MAX_WORKBOOK_CELLS = 100_000;
    public static final int MAX_MERGED_REGIONS = 5_000;
    public static final int MAX_CELL_CHARACTERS = 20_000;
    public static final int MAX_TOTAL_CHARACTERS = 2_000_000;
    private static final int MAX_PDF_PAGES = 500;
    private static final Duration LIBREOFFICE_DEADLINE = Duration.ofSeconds(15);
    private static final Pattern PLACEHOLDER =
            Pattern.compile(
                    "\\{\\{\\s*([\\p{L}_][\\p{L}\\p{N}_.-]{0,63})\\s*}}", Pattern.UNICODE_CASE);
    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern EXTERNAL_OR_DYNAMIC_FORMULA =
            Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}_])(?:(?:_xlfn|COM\\.MICROSOFT)\\.)?"
                            + "(?:WEBSERVICE|HYPERLINK|RTD|DDE|SQL\\.REQUEST|FILTERXML)\\s*\\(");

    public static final class InvalidTemplate extends IOException {
        public InvalidTemplate(String message) {
            super(message);
        }
    }

    public static final class ConversionUnavailable extends IOException {
        public ConversionUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DocumentTemplateProcessor() {}

    public static OfficeWorkerProtocol.Inspection inspect(String filename, byte[] bytes)
            throws IOException {
        requireDocument(filename, bytes);
        verifySafeOpenXml(bytes);
        if (extension(filename).equals("docx")) return inspectWord(bytes);
        return inspectWorkbookTemplate(bytes);
    }

    public static byte[] fillAndConvert(
            String filename,
            byte[] bytes,
            Map<String, String> bindings,
            Map<String, String> values,
            Path requestDirectory)
            throws IOException, InterruptedException {
        requireDocument(filename, bytes);
        validateBindings(bindings, values);
        verifySafeOpenXml(bytes);
        Path taskDirectory = Files.createTempDirectory(requestDirectory, "document-");
        Path sourceDirectory = Files.createDirectory(taskDirectory.resolve("source"));
        Path outputDirectory = Files.createDirectory(taskDirectory.resolve("output"));
        Path profileDirectory = Files.createDirectory(taskDirectory.resolve("profile"));
        Path source =
                sourceDirectory.resolve(
                        extension(filename).equals("docx") ? "template.docx" : "template.xlsx");
        Files.write(source, bytes);
        if (extension(filename).equals("docx")) fillWord(source, bindings, values);
        else fillWorkbook(source, bindings, values);
        Path pdfPath;
        try {
            pdfPath = convertToPdf(source, outputDirectory, profileDirectory, taskDirectory);
        } catch (IOException failure) {
            throw new ConversionUnavailable("LibreOffice conversion failed", failure);
        }
        long size = Files.size(pdfPath);
        if (size < 8 || size > MAX_RESPONSE_PDF_BYTES)
            throw new OfficeArchiveBudget.Exceeded("Generated PDF exceeds its output limit");
        byte[] pdf = Files.readAllBytes(pdfPath);
        try {
            validatePdf(pdf);
        } catch (IOException failure) {
            throw new ConversionUnavailable("Generated PDF failed validation", failure);
        }
        return pdf;
    }

    public static OfficeWorkerProtocol.WorkbookData readWorkbook(byte[] bytes) throws IOException {
        requireWorkbook(bytes);
        verifySafeOpenXml(bytes);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            requireMergedRegionLimit(workbook);
            if (workbook.getNumberOfSheets() == 0)
                return new OfficeWorkerProtocol.WorkbookData(List.of());
            var sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            if (lastRow + 1 > MAX_WORKBOOK_ROWS)
                throw new OfficeArchiveBudget.Exceeded("Workbook row limit exceeded");
            int maximumColumns = 0;
            for (Row row : sheet) {
                short lastCell = row.getLastCellNum();
                if (lastCell > maximumColumns) maximumColumns = lastCell;
            }
            if (maximumColumns > MAX_WORKBOOK_COLUMNS
                    || (long) (lastRow + 1) * maximumColumns > MAX_WORKBOOK_CELLS)
                throw new OfficeArchiveBudget.Exceeded("Workbook cell limit exceeded");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            List<List<String>> rows = new ArrayList<>();
            long totalCharacters = 0;
            int lastNonemptyRow = -1;
            for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> values = new ArrayList<>();
                int lastNonemptyColumn = -1;
                for (int column = 0; column < maximumColumns; column++) {
                    Cell cell =
                            row == null
                                    ? null
                                    : row.getCell(
                                            column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    checkCellText(value);
                    totalCharacters += value.length();
                    if (totalCharacters > MAX_TOTAL_CHARACTERS)
                        throw new OfficeArchiveBudget.Exceeded("Workbook text limit exceeded");
                    values.add(value);
                    if (!value.isEmpty()) lastNonemptyColumn = column;
                }
                if (lastNonemptyColumn >= 0) lastNonemptyRow = rowIndex;
                if (lastNonemptyColumn + 1 < values.size())
                    values = new ArrayList<>(values.subList(0, lastNonemptyColumn + 1));
                rows.add(values);
            }
            if (lastNonemptyRow + 1 < rows.size())
                rows = new ArrayList<>(rows.subList(0, lastNonemptyRow + 1));
            return new OfficeWorkerProtocol.WorkbookData(rows.stream().map(List::copyOf).toList());
        } catch (OfficeArchiveBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (Exception malformed) {
            throw new IOException("Invalid workbook", malformed);
        }
    }

    public static byte[] writeWorkbook(List<List<String>> rows) throws IOException {
        validateRows(rows);
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Data");
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex);
                List<String> values = rows.get(rowIndex);
                for (int column = 0; column < values.size(); column++) {
                    String value = values.get(column);
                    if (value != null) row.createCell(column, CellType.STRING).setCellValue(value);
                }
            }
            workbook.write(output);
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_INPUT_BYTES)
                throw new OfficeArchiveBudget.Exceeded(
                        "Generated workbook exceeds its output limit");
            return bytes;
        } catch (OfficeArchiveBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (Exception failure) {
            throw new IOException("Workbook generation failed", failure);
        }
    }

    private static OfficeWorkerProtocol.Inspection inspectWord(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var slots = new ArrayList<OfficeWorkerProtocol.Slot>();
            var placeholders = new LinkedHashSet<String>();
            var textBudget = new TextBudget();
            collectBody(document, "word", slots, placeholders, textBudget);
            for (int index = 0; index < document.getHeaderList().size(); index++)
                collectBody(
                        document.getHeaderList().get(index),
                        "word:header" + index,
                        slots,
                        placeholders,
                        textBudget);
            for (int index = 0; index < document.getFooterList().size(); index++)
                collectBody(
                        document.getFooterList().get(index),
                        "word:footer" + index,
                        slots,
                        placeholders,
                        textBudget);
            return inspection(slots, placeholders);
        } catch (OfficeArchiveBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (Exception malformed) {
            throw new IOException("Invalid Word template", malformed);
        }
    }

    private static OfficeWorkerProtocol.Inspection inspectWorkbookTemplate(byte[] bytes)
            throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            requireMergedRegionLimit(workbook);
            var slots = new ArrayList<OfficeWorkerProtocol.Slot>();
            var placeholders = new LinkedHashSet<String>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            long scanned = 0;
            long totalCharacters = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                int lastRow = sheet.getLastRowNum();
                if (lastRow + 1 > MAX_WORKBOOK_ROWS)
                    throw new OfficeArchiveBudget.Exceeded("Workbook row limit exceeded");
                int maximumColumns = 0;
                for (Row row : sheet) {
                    short lastCell = row.getLastCellNum();
                    if (lastCell > maximumColumns) maximumColumns = lastCell;
                }
                if (maximumColumns > MAX_WORKBOOK_COLUMNS)
                    throw new OfficeArchiveBudget.Exceeded("Workbook column limit exceeded");
                scanned += (long) (lastRow + 1) * maximumColumns;
                if (scanned > MAX_WORKBOOK_CELLS)
                    throw new OfficeArchiveBudget.Exceeded("Workbook cell limit exceeded");
                Set<Long> mergedNonAnchors =
                        mergedNonAnchorCells(sheet, lastRow + 1, maximumColumns);
                for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    for (int column = 0; column < maximumColumns; column++) {
                        if (nonAnchorMergedCell(mergedNonAnchors, rowIndex, column)) continue;
                        Cell cell =
                                row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                        String value = cell == null ? "" : formatter.formatCellValue(cell);
                        checkCellText(value);
                        totalCharacters += value.length();
                        if (totalCharacters > MAX_TOTAL_CHARACTERS)
                            throw new OfficeArchiveBudget.Exceeded("Workbook text limit exceeded");
                        collectPlaceholders(value, placeholders);
                        if (emptySlotCell(cell, value)) {
                            addSlot(
                                    slots,
                                    new OfficeWorkerProtocol.Slot(
                                            excelSlotKey(sheetIndex, rowIndex, column),
                                            excelSlotLabel(
                                                    sheet,
                                                    workbook.getSheetName(sheetIndex),
                                                    rowIndex,
                                                    column),
                                            ""));
                        }
                    }
                }
            }
            return inspection(slots, placeholders);
        } catch (OfficeArchiveBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (Exception malformed) {
            throw new IOException("Invalid workbook template", malformed);
        }
    }

    private static void collectBody(
            IBody body,
            String prefix,
            List<OfficeWorkerProtocol.Slot> slots,
            Set<String> placeholders,
            TextBudget textBudget)
            throws IOException {
        int paragraphIndex = 0;
        int tableIndex = 0;
        for (IBodyElement element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                String key = prefix + ":p" + paragraphIndex;
                String text = paragraphText(paragraph);
                textBudget.add(text);
                if (text.isBlank())
                    addSlot(
                            slots,
                            new OfficeWorkerProtocol.Slot(
                                    key, paragraphLabel(body, paragraphIndex), ""));
                collectPlaceholders(text, placeholders);
                paragraphIndex++;
            } else if (element instanceof XWPFTable table) {
                collectTable(
                        table, prefix + ":t" + tableIndex, slots, placeholders, null, textBudget);
                tableIndex++;
            }
        }
    }

    private static void collectTable(
            XWPFTable table,
            String keyPrefix,
            List<OfficeWorkerProtocol.Slot> slots,
            Set<String> placeholders,
            Map<String, XWPFParagraph> paragraphs,
            TextBudget textBudget)
            throws IOException {
        for (int rowIndex = 0; rowIndex < table.getRows().size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            for (int column = 0; column < row.getTableCells().size(); column++) {
                XWPFTableCell cell = row.getCell(column);
                String key = keyPrefix + ":r" + rowIndex + ":c" + column;
                String text = java.util.Objects.requireNonNullElse(cell.getText(), "");
                if (text.isBlank()) {
                    textBudget.add(text);
                    addSlot(
                            slots,
                            new OfficeWorkerProtocol.Slot(
                                    key,
                                    "Table "
                                            + keyPrefix.substring(keyPrefix.lastIndexOf(':') + 1)
                                            + ", row "
                                            + (rowIndex + 1)
                                            + ", cell "
                                            + (column + 1)
                                            + adjacentCellLabel(row, column),
                                    ""));
                    if (paragraphs != null && !cell.getParagraphs().isEmpty())
                        paragraphs.put(key, cell.getParagraphs().getFirst());
                } else if (paragraphs == null) {
                    collectBody(cell, key, slots, placeholders, textBudget);
                } else collectBodyForFill(cell, key, slots, paragraphs, placeholders, textBudget);
                collectPlaceholders(text, placeholders);
            }
        }
    }

    private static void fillWord(
            Path source, Map<String, String> bindings, Map<String, String> values)
            throws IOException {
        try (var document = new XWPFDocument(Files.newInputStream(source))) {
            var slots = new ArrayList<OfficeWorkerProtocol.Slot>();
            var paragraphs = new LinkedHashMap<String, XWPFParagraph>();
            var placeholders = new LinkedHashSet<String>();
            var textBudget = new TextBudget();
            collectBodyForFill(document, "word", slots, paragraphs, placeholders, textBudget);
            for (int index = 0; index < document.getHeaderList().size(); index++)
                collectBodyForFill(
                        document.getHeaderList().get(index),
                        "word:header" + index,
                        slots,
                        paragraphs,
                        placeholders,
                        textBudget);
            for (int index = 0; index < document.getFooterList().size(); index++)
                collectBodyForFill(
                        document.getFooterList().get(index),
                        "word:footer" + index,
                        slots,
                        paragraphs,
                        placeholders,
                        textBudget);
            validateTemplateBindings(bindings, values, slots, placeholders);
            for (XWPFParagraph paragraph : paragraphs.values()) replaceParagraph(paragraph, values);
            for (var slot : slots) {
                String field = bindings.get(slot.key());
                if (field == null || !values.containsKey(field)) continue;
                XWPFParagraph paragraph = paragraphs.get(slot.key());
                if (paragraph != null) {
                    setParagraphValue(paragraph, values.get(field));
                    continue;
                }
            }
            try (var output = Files.newOutputStream(source)) {
                document.write(output);
            }
        } catch (OfficeArchiveBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (InvalidTemplate invalid) {
            throw invalid;
        } catch (Exception failure) {
            throw new IOException("Word template processing failed", failure);
        }
    }

    private static void collectBodyForFill(
            IBody body,
            String prefix,
            List<OfficeWorkerProtocol.Slot> slots,
            Map<String, XWPFParagraph> paragraphs,
            Set<String> placeholders,
            TextBudget textBudget)
            throws IOException {
        int paragraphIndex = 0;
        int tableIndex = 0;
        for (IBodyElement element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                String key = prefix + ":p" + paragraphIndex;
                String text = paragraphText(paragraph);
                textBudget.add(text);
                paragraphs.put(key, paragraph);
                if (text.isBlank())
                    addSlot(
                            slots,
                            new OfficeWorkerProtocol.Slot(
                                    key, paragraphLabel(body, paragraphIndex), ""));
                collectPlaceholders(text, placeholders);
                paragraphIndex++;
            } else if (element instanceof XWPFTable table) {
                collectTable(
                        table,
                        prefix + ":t" + tableIndex,
                        slots,
                        placeholders,
                        paragraphs,
                        textBudget);
                tableIndex++;
            }
        }
    }

    private static void fillWorkbook(
            Path source, Map<String, String> bindings, Map<String, String> values)
            throws IOException {
        Files.write(source, fillWorkbook(Files.readAllBytes(source), bindings, values));
    }

    static byte[] fillWorkbook(
            byte[] sourceBytes, Map<String, String> bindings, Map<String, String> values)
            throws IOException {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(sourceBytes))) {
            requireMergedRegionLimit(workbook);
            var slots = new ArrayList<OfficeWorkerProtocol.Slot>();
            var placeholders = new LinkedHashSet<String>();
            var formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            var mergedNonAnchorsBySheet = new ArrayList<Set<Long>>();
            long scanned = 0;
            long totalCharacters = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                int maxRow = sheet.getLastRowNum();
                if (maxRow + 1 > MAX_WORKBOOK_ROWS)
                    throw new OfficeArchiveBudget.Exceeded("Workbook row limit exceeded");
                int maxColumn = 0;
                for (Row row : sheet) maxColumn = Math.max(maxColumn, row.getLastCellNum());
                if (maxColumn > MAX_WORKBOOK_COLUMNS)
                    throw new OfficeArchiveBudget.Exceeded("Workbook column limit exceeded");
                scanned += (long) (maxRow + 1) * maxColumn;
                if (scanned > MAX_WORKBOOK_CELLS)
                    throw new OfficeArchiveBudget.Exceeded("Workbook cell limit exceeded");
                Set<Long> mergedNonAnchors = mergedNonAnchorCells(sheet, maxRow + 1, maxColumn);
                mergedNonAnchorsBySheet.add(mergedNonAnchors);
                for (int rowIndex = 0; rowIndex <= maxRow; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    for (int column = 0; column < maxColumn; column++) {
                        Cell cell =
                                row.getCell(column, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                        if (cell != null && cell.getCellType() == CellType.FORMULA)
                            validateWorkbookFormula(cell.getCellFormula());
                        if (nonAnchorMergedCell(mergedNonAnchors, rowIndex, column)) continue;
                        String text = cell == null ? "" : formatter.formatCellValue(cell);
                        checkCellText(text);
                        totalCharacters += text.length();
                        if (totalCharacters > MAX_TOTAL_CHARACTERS)
                            throw new OfficeArchiveBudget.Exceeded("Workbook text limit exceeded");
                        if (emptySlotCell(cell, text))
                            addSlot(
                                    slots,
                                    new OfficeWorkerProtocol.Slot(
                                            excelSlotKey(sheetIndex, rowIndex, column),
                                            excelSlotLabel(
                                                    sheet,
                                                    workbook.getSheetName(sheetIndex),
                                                    rowIndex,
                                                    column),
                                            ""));
                        collectPlaceholders(text, placeholders);
                    }
                }
            }
            validateTemplateBindings(bindings, values, slots, placeholders);
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                Set<Long> mergedNonAnchors = mergedNonAnchorsBySheet.get(sheetIndex);
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (nonAnchorMergedCell(
                                mergedNonAnchors, cell.getRowIndex(), cell.getColumnIndex()))
                            continue;
                        if (cell.getCellType() != CellType.STRING) continue;
                        String replaced = replacePlaceholders(cell.getStringCellValue(), values);
                        if (!replaced.equals(cell.getStringCellValue()))
                            setWorkbookText(cell, replaced);
                    }
                }
            }
            for (var entry : bindings.entrySet()) {
                String value = values.get(entry.getValue());
                if (value == null
                        || !slots.stream().anyMatch(slot -> slot.key().equals(entry.getKey())))
                    continue;
                int[] coordinate = parseExcelSlotKey(entry.getKey());
                var row = workbook.getSheetAt(coordinate[0]).getRow(coordinate[1]);
                if (row == null) row = workbook.getSheetAt(coordinate[0]).createRow(coordinate[1]);
                Cell cell = row.getCell(coordinate[2]);
                if (cell == null) cell = row.createCell(coordinate[2], CellType.BLANK);
                setWorkbookText(cell, value);
            }
            try (var output = new ByteArrayOutputStream()) {
                workbook.write(output);
                byte[] result = output.toByteArray();
                if (result.length > MAX_INPUT_BYTES)
                    throw new OfficeArchiveBudget.Exceeded(
                            "Filled workbook exceeds its output limit");
                return result;
            }
        } catch (OfficeArchiveBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (InvalidTemplate invalid) {
            throw invalid;
        } catch (Exception failure) {
            throw new IOException("Workbook template processing failed", failure);
        }
    }

    private static OfficeWorkerProtocol.Inspection inspection(
            List<OfficeWorkerProtocol.Slot> slots, Set<String> placeholders) {
        return new OfficeWorkerProtocol.Inspection(List.copyOf(slots), List.copyOf(placeholders));
    }

    static void validateWorkbookFormula(String formula) throws InvalidTemplate {
        StringBuilder outsideStrings = new StringBuilder(formula.length());
        boolean inString = false;
        for (int index = 0; index < formula.length(); index++) {
            char character = formula.charAt(index);
            if (character == '"') {
                outsideStrings.append(' ');
                if (inString && index + 1 < formula.length() && formula.charAt(index + 1) == '"') {
                    outsideStrings.append(' ');
                    index++;
                } else {
                    inString = !inString;
                }
            } else {
                outsideStrings.append(inString ? ' ' : character);
            }
        }
        if (inString) throw new InvalidTemplate("Malformed workbook formula");
        if (EXTERNAL_OR_DYNAMIC_FORMULA.matcher(outsideStrings).find())
            throw new InvalidTemplate("External or dynamic workbook formulas are not allowed");
    }

    private static void addSlot(
            List<OfficeWorkerProtocol.Slot> slots, OfficeWorkerProtocol.Slot slot)
            throws OfficeArchiveBudget.Exceeded {
        if (slots.size() >= MAX_SLOTS)
            throw new OfficeArchiveBudget.Exceeded("Template slot limit exceeded");
        slots.add(slot);
    }

    private static void collectPlaceholders(String text, Set<String> placeholders)
            throws OfficeArchiveBudget.Exceeded {
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (placeholders.size() >= MAX_PLACEHOLDERS && !placeholders.contains(matcher.group(1)))
                throw new OfficeArchiveBudget.Exceeded("Template placeholder limit exceeded");
            placeholders.add(matcher.group(1));
        }
    }

    private static void validateTemplateBindings(
            Map<String, String> bindings,
            Map<String, String> values,
            List<OfficeWorkerProtocol.Slot> slots,
            Set<String> placeholders)
            throws InvalidTemplate {
        Set<String> validSlots = new LinkedHashSet<>();
        slots.forEach(slot -> validSlots.add(slot.key()));
        for (var entry : bindings.entrySet()) {
            if (!validSlots.contains(entry.getKey())
                    || !FIELD_KEY.matcher(entry.getValue()).matches()
                    || !values.containsKey(entry.getValue()))
                throw new InvalidTemplate("Unknown or incomplete template binding");
        }
        for (String slot : validSlots)
            if (!bindings.containsKey(slot))
                throw new InvalidTemplate("A template slot has no field binding");
        for (String field : placeholders)
            if (!values.containsKey(field))
                throw new InvalidTemplate("A template placeholder has no value");
    }

    private static void validateBindings(Map<String, String> bindings, Map<String, String> values)
            throws OfficeArchiveBudget.Exceeded {
        if (bindings == null
                || values == null
                || bindings.size() > MAX_SLOTS
                || values.size() > 1_000)
            throw new OfficeArchiveBudget.Exceeded("Document metadata exceeds its limit");
        long characters = 0;
        for (var entry : bindings.entrySet()) {
            if (entry.getKey() == null || entry.getKey().length() > 256 || entry.getValue() == null)
                throw new OfficeArchiveBudget.Exceeded("Invalid template binding");
            characters += entry.getKey().length() + entry.getValue().length();
        }
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().length() > 64
                    || entry.getValue() == null
                    || entry.getValue().length() > MAX_CELL_CHARACTERS)
                throw new OfficeArchiveBudget.Exceeded("Invalid document value");
            characters += entry.getKey().length() + entry.getValue().length();
        }
        if (characters > MAX_TOTAL_CHARACTERS)
            throw new OfficeArchiveBudget.Exceeded("Document values exceed their limit");
    }

    private static void validateRows(List<List<String>> rows) throws OfficeArchiveBudget.Exceeded {
        if (rows == null || rows.size() > MAX_WORKBOOK_ROWS)
            throw new OfficeArchiveBudget.Exceeded("Workbook row limit exceeded");
        long cells = 0;
        long characters = 0;
        for (List<String> row : rows) {
            if (row == null || row.size() > MAX_WORKBOOK_COLUMNS)
                throw new OfficeArchiveBudget.Exceeded("Workbook column limit exceeded");
            cells += row.size();
            if (cells > MAX_WORKBOOK_CELLS)
                throw new OfficeArchiveBudget.Exceeded("Workbook cell limit exceeded");
            for (String value : row) {
                if (value == null) continue;
                checkCellText(value);
                characters += value.length();
                if (characters > MAX_TOTAL_CHARACTERS)
                    throw new OfficeArchiveBudget.Exceeded("Workbook text limit exceeded");
            }
        }
    }

    private static void replaceParagraph(XWPFParagraph paragraph, Map<String, String> values)
            throws IOException {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) return;
        List<String> runTexts = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (XWPFRun run : runs) {
            String text = runText(run);
            runTexts.add(text);
            joined.append(text);
        }
        Matcher matcher = PLACEHOLDER.matcher(joined);
        List<Replacement> replacements = new ArrayList<>();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value != null)
                replacements.add(new Replacement(matcher.start(), matcher.end(), value));
        }
        for (int index = replacements.size() - 1; index >= 0; index--) {
            Replacement replacement = replacements.get(index);
            applyReplacement(runs, runTexts, replacement);
        }
    }

    private static void applyReplacement(
            List<XWPFRun> runs, List<String> runTexts, Replacement replacement) {
        int startRun = -1;
        int endRun = -1;
        int startOffset = 0;
        int endOffset = 0;
        int cursor = 0;
        for (int index = 0; index < runTexts.size(); index++) {
            String text = runTexts.get(index);
            int next = cursor + text.length();
            if (startRun < 0 && replacement.start() >= cursor && replacement.start() < next) {
                startRun = index;
                startOffset = replacement.start() - cursor;
            }
            if (replacement.end() > cursor && replacement.end() <= next) {
                endRun = index;
                endOffset = replacement.end() - cursor;
                break;
            }
            cursor = next;
        }
        if (startRun < 0 || endRun < 0) return;
        if (startRun == endRun) {
            String text = runTexts.get(startRun);
            String next =
                    text.substring(0, startOffset)
                            + replacement.value()
                            + text.substring(endOffset);
            setRunText(runs.get(startRun), next);
            runTexts.set(startRun, next);
            return;
        }
        String first = runTexts.get(startRun);
        setRunText(runs.get(startRun), first.substring(0, startOffset) + replacement.value());
        runTexts.set(startRun, first.substring(0, startOffset) + replacement.value());
        for (int index = startRun + 1; index < endRun; index++) {
            setRunText(runs.get(index), "");
            runTexts.set(index, "");
        }
        String last = runTexts.get(endRun);
        setRunText(runs.get(endRun), last.substring(endOffset));
        runTexts.set(endRun, last.substring(endOffset));
    }

    private static void setRunText(XWPFRun run, String value) {
        if (run.getCTR().sizeOfTArray() == 0) run.setText(value);
        else {
            run.setText(value, 0);
            for (int index = run.getCTR().sizeOfTArray() - 1; index > 0; index--)
                run.getCTR().removeT(index);
        }
    }

    private static void setParagraphValue(XWPFParagraph paragraph, String value) {
        if (paragraph.getRuns().isEmpty()) paragraph.createRun().setText(value);
        else setRunText(paragraph.getRuns().getFirst(), value);
    }

    private static String replacePlaceholders(String text, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null)
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            else matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void setWorkbookText(Cell cell, String value) {
        // POI 5.5.x leaves the inline-string <is> node in place when setCellValue is called.
        // Clearing to blank preserves the cell style and lets POI write a valid shared string.
        cell.setBlank();
        cell.setCellValue(value);
    }

    private static String paragraphText(XWPFParagraph paragraph) {
        StringBuilder result = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) result.append(runText(run));
        return result.toString();
    }

    private static String runText(XWPFRun run) {
        return java.util.Objects.requireNonNullElse(run.text(), "");
    }

    private static String paragraphLabel(IBody body, int paragraphIndex) {
        List<XWPFParagraph> paragraphs = body.getParagraphs();
        if (paragraphIndex <= 0 || paragraphIndex > paragraphs.size())
            return "Paragraph " + (paragraphIndex + 1);
        String neighbor = shortLabel(paragraphText(paragraphs.get(paragraphIndex - 1)));
        return neighbor.isEmpty()
                ? "Paragraph " + (paragraphIndex + 1)
                : "Paragraph " + (paragraphIndex + 1) + " · " + neighbor;
    }

    private static String adjacentCellLabel(XWPFTableRow row, int column) {
        if (column <= 0) return "";
        String neighbor = shortLabel(row.getCell(column - 1).getText());
        return neighbor.isEmpty() ? "" : " · " + neighbor;
    }

    private static String excelSlotLabel(
            org.apache.poi.xssf.usermodel.XSSFSheet sheet,
            String sheetName,
            int rowIndex,
            int column) {
        String reference = new CellReference(rowIndex, column).formatAsString();
        String neighbor = shortLabel(neighborCellText(sheet, rowIndex, column));
        return sheetName + "!" + reference + (neighbor.isEmpty() ? "" : " · " + neighbor);
    }

    private static String neighborCellText(
            org.apache.poi.xssf.usermodel.XSSFSheet sheet, int rowIndex, int column) {
        Row row = sheet.getRow(rowIndex);
        if (row != null && column > 0) {
            Cell left = row.getCell(column - 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (left != null && left.getCellType() != CellType.BLANK) {
                String text = new DataFormatter(Locale.ROOT).formatCellValue(left);
                if (!text.isBlank()) return text;
            }
        }
        if (rowIndex > 0) {
            Row aboveRow = sheet.getRow(rowIndex - 1);
            Cell above =
                    aboveRow == null
                            ? null
                            : aboveRow.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (above != null && above.getCellType() != CellType.BLANK) {
                String text = new DataFormatter(Locale.ROOT).formatCellValue(above);
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private static String shortLabel(String source) {
        if (source == null) return "";
        String text = source.strip().replaceAll("\\s+", " ");
        int codePoints = text.codePointCount(0, text.length());
        return codePoints > 60 ? text.substring(0, text.offsetByCodePoints(0, 60)) + "…" : text;
    }

    private static void requireMergedRegionLimit(XSSFWorkbook workbook)
            throws OfficeArchiveBudget.Exceeded {
        for (var sheet : workbook) {
            if (sheet.getNumMergedRegions() > MAX_MERGED_REGIONS)
                throw new OfficeArchiveBudget.Exceeded("Workbook merged-region limit exceeded");
        }
    }

    private static Set<Long> mergedNonAnchorCells(XSSFSheet sheet, int rows, int columns)
            throws OfficeArchiveBudget.Exceeded {
        if (sheet.getNumMergedRegions() > MAX_MERGED_REGIONS)
            throw new OfficeArchiveBudget.Exceeded("Workbook merged-region limit exceeded");
        var cells = new HashSet<Long>();
        long inspected = 0;
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            int firstRow = Math.max(0, range.getFirstRow());
            int lastRow = Math.min(rows - 1, range.getLastRow());
            int firstColumn = Math.max(0, range.getFirstColumn());
            int lastColumn = Math.min(columns - 1, range.getLastColumn());
            for (int row = firstRow; row <= lastRow; row++) {
                for (int column = firstColumn; column <= lastColumn; column++) {
                    if (++inspected > MAX_WORKBOOK_CELLS)
                        throw new OfficeArchiveBudget.Exceeded(
                                "Workbook merged-cell limit exceeded");
                    if (range.getFirstRow() != row || range.getFirstColumn() != column)
                        cells.add(((long) row << 32) | (column & 0xffff_ffffL));
                }
            }
        }
        return cells;
    }

    private static boolean nonAnchorMergedCell(Set<Long> cells, int row, int column) {
        return cells.contains(((long) row << 32) | (column & 0xffff_ffffL));
    }

    private static Path convertToPdf(
            Path source, Path outputDirectory, Path profileDirectory, Path taskDirectory)
            throws IOException, InterruptedException {
        String userInstallation = profileDirectory.toUri().toASCIIString();
        var builder =
                new ProcessBuilder(
                        "libreoffice",
                        "--headless",
                        "--nologo",
                        "--nodefault",
                        "--norestore",
                        "--nolockcheck",
                        "-env:UserInstallation=" + userInstallation,
                        "--convert-to",
                        "pdf",
                        "--outdir",
                        outputDirectory.toString(),
                        source.toString());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("HOME", taskDirectory.toString());
        builder.environment().put("XDG_CACHE_HOME", taskDirectory.resolve("cache").toString());
        builder.environment().put("XDG_CONFIG_HOME", taskDirectory.resolve("config").toString());
        builder.environment().put("TMPDIR", taskDirectory.toString());
        builder.environment().put("OMP_NUM_THREADS", "1");
        Process process = builder.start();
        if (!process.waitFor(
                LIBREOFFICE_DEADLINE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            terminateTree(process);
            throw new IOException("LibreOffice conversion deadline exceeded");
        }
        if (process.exitValue() != 0) throw new IOException("LibreOffice conversion failed");
        Path output = outputDirectory.resolve("template.pdf");
        if (!Files.isRegularFile(output)) throw new IOException("LibreOffice produced no PDF");
        return output;
    }

    private static void terminateTree(Process process) throws InterruptedException {
        var descendants = process.toHandle().descendants().toList();
        for (var child : descendants) child.destroyForcibly();
        process.destroyForcibly();
        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void validatePdf(byte[] bytes) throws IOException {
        if (bytes.length < 8
                || bytes.length > MAX_RESPONSE_PDF_BYTES
                || !new String(bytes, 0, Math.min(bytes.length, 5), StandardCharsets.US_ASCII)
                        .startsWith("%PDF-"))
            throw new IOException("LibreOffice output is not a bounded PDF");
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()
                    || pdf.getNumberOfPages() < 1
                    || pdf.getNumberOfPages() > MAX_PDF_PAGES)
                throw new IOException("PDF page count or encryption is not allowed");
            for (var page : pdf.getPages()) {
                var box = page.getMediaBox();
                if (box.getWidth() <= 0
                        || box.getHeight() <= 0
                        || box.getWidth() > 14_400
                        || box.getHeight() > 14_400)
                    throw new IOException("PDF page dimensions exceed their limit");
            }
        } catch (RuntimeException malformed) {
            throw new IOException("LibreOffice output failed PDF validation", malformed);
        }
    }

    private static void verifySafeOpenXml(byte[] bytes) throws IOException {
        OfficeArchiveBudget.verify(bytes);
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = archive.getNextEntry()) != null; ) {
                rejectUnsafeArchiveEntry(entry.getName());
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.contains("vbaproject")
                        || name.contains("externallinks/")
                        || name.contains("/embeddings/")
                        || name.contains("activex/")
                        || name.endsWith(".bin"))
                    throw new IOException("Active or external Office content is not allowed");
                if (name.endsWith(".rels") || name.equals("[content_types].xml"))
                    inspectRelationshipXml(archive, name.endsWith(".rels"));
                else drain(archive);
                archive.closeEntry();
            }
        } catch (OfficeArchiveBudget.Exceeded exceeded) {
            throw exceeded;
        } catch (XMLStreamException malformed) {
            throw new IOException("Invalid Office relationship metadata", malformed);
        }
    }

    private static void rejectUnsafeArchiveEntry(String name) throws IOException {
        if (name.isBlank()
                || name.indexOf('\0') >= 0
                || name.startsWith("/")
                || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0) throw new IOException("Unsafe Office archive part name");
        for (String segment : name.split("/", -1))
            if (".".equals(segment) || "..".equals(segment))
                throw new IOException("Unsafe Office archive part name");
    }

    private static void inspectRelationshipXml(InputStream input, boolean relationships)
            throws XMLStreamException, IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        XMLStreamReader reader =
                factory.createXMLStreamReader(
                        new java.io.FilterInputStream(input) {
                            @Override
                            public void close() {
                                // The archive loop owns this stream and closes each ZIP entry.
                            }
                        });
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                for (int index = 0; index < reader.getAttributeCount(); index++) {
                    String name = reader.getAttributeLocalName(index);
                    String value = reader.getAttributeValue(index);
                    if (relationships
                            && "TargetMode".equals(name)
                            && "External".equalsIgnoreCase(value))
                        throw new IOException("External Office relationships are not allowed");
                    if (!relationships
                            && "ContentType".equals(name)
                            && (value.toLowerCase(Locale.ROOT).contains("macroenabled")
                                    || value.toLowerCase(Locale.ROOT).contains("vbaproject")))
                        throw new IOException("Macro-enabled Office files are not allowed");
                }
            }
        } finally {
            reader.close();
        }
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        while (input.read(buffer) >= 0) {}
    }

    private static void requireDocument(String filename, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("Empty Office template");
        if (bytes.length > MAX_INPUT_BYTES)
            throw new OfficeArchiveBudget.Exceeded("Input too large");
        if (!List.of("docx", "xlsx").contains(extension(filename)))
            throw new IOException("Unsupported template type");
    }

    private static void requireWorkbook(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("Empty workbook");
        if (bytes.length > MAX_INPUT_BYTES)
            throw new OfficeArchiveBudget.Exceeded("Input too large");
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void checkCellText(String text) throws OfficeArchiveBudget.Exceeded {
        if (text.length() > MAX_CELL_CHARACTERS)
            throw new OfficeArchiveBudget.Exceeded("Cell text limit exceeded");
    }

    private static boolean emptySlotCell(Cell cell, String text) {
        return text.isBlank()
                && (cell == null
                        || cell.getCellType() == CellType.BLANK
                        || cell.getCellType() == CellType.STRING);
    }

    private static String excelSlotKey(int sheet, int row, int column) {
        return "xlsx:s" + sheet + ":r" + row + ":c" + column;
    }

    private static int[] parseExcelSlotKey(String key) throws IOException {
        Matcher matcher = Pattern.compile("xlsx:s(\\d+):r(\\d+):c(\\d+)").matcher(key);
        if (!matcher.matches()) throw new IOException("Invalid workbook slot key");
        try {
            int sheet = Integer.parseInt(matcher.group(1));
            int row = Integer.parseInt(matcher.group(2));
            int column = Integer.parseInt(matcher.group(3));
            if (sheet < 0
                    || row < 0
                    || row >= MAX_WORKBOOK_ROWS
                    || column < 0
                    || column >= MAX_WORKBOOK_COLUMNS)
                throw new IOException("Workbook slot key exceeds its limit");
            return new int[] {sheet, row, column};
        } catch (NumberFormatException malformed) {
            throw new IOException("Invalid workbook slot key", malformed);
        }
    }

    private record Replacement(int start, int end, String value) {}

    private static final class TextBudget {
        private long characters;

        private void add(String text) throws OfficeArchiveBudget.Exceeded {
            characters += text.length();
            if (characters > MAX_TOTAL_CHARACTERS)
                throw new OfficeArchiveBudget.Exceeded("Template text limit exceeded");
        }
    }
}
