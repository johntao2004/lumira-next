package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocumentTemplateProcessorTest {
    @Test
    void inspectsUnicodePlaceholdersAcrossWordRunsAndFindsBlankParagraphSlots() throws Exception {
        byte[] docx;
        try (var document = new XWPFDocument();
                var output = new ByteArrayOutputStream()) {
            var paragraph = document.createParagraph();
            paragraph.createRun().setText("姓名：{{用户.");
            paragraph.createRun().setText("姓名}}");
            document.createParagraph();
            document.write(output);
            docx = output.toByteArray();
        }

        var result = DocumentTemplateProcessor.inspect("登记.docx", docx);

        assertEquals(List.of("用户.姓名"), result.placeholders());
        assertEquals(1, result.slots().size());
        assertEquals("word:p1", result.slots().getFirst().key());
    }

    @Test
    void excludesMergedExcelNonAnchorSlotsAndIncludesNearbyCellLabels() throws Exception {
        byte[] xlsx;
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("登记");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("姓名");
            row.createCell(1).setCellValue("");
            row.createCell(2, CellType.BLANK);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 1, 2));
            row.createCell(3).setCellValue("{{客户.编号}}");
            workbook.write(output);
            xlsx = output.toByteArray();
        }

        var result = DocumentTemplateProcessor.inspect("登记.xlsx", xlsx);

        assertEquals(List.of("客户.编号"), result.placeholders());
        assertTrue(result.slots().stream().anyMatch(slot -> slot.key().equals("xlsx:s0:r0:c1")));
        assertFalse(result.slots().stream().anyMatch(slot -> slot.key().equals("xlsx:s0:r0:c2")));
        assertTrue(
                result.slots().stream()
                        .filter(slot -> slot.key().equals("xlsx:s0:r0:c1"))
                        .findFirst()
                        .orElseThrow()
                        .label()
                        .contains("姓名"));
    }

    @Test
    void writesAndReadsARealStringOnlyXlsxWorkbook() throws Exception {
        byte[] xlsx =
                DocumentTemplateProcessor.writeWorkbook(
                        List.of(List.of("姓名", "内容"), List.of("林", "=1+1")));

        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var formulaLike = workbook.getSheetAt(0).getRow(1).getCell(1);
            assertEquals(CellType.STRING, formulaLike.getCellType());
            assertEquals("=1+1", formulaLike.getStringCellValue());
        }
        assertEquals(
                List.of(List.of("姓名", "内容"), List.of("林", "=1+1")),
                DocumentTemplateProcessor.readWorkbook(xlsx).rows());
    }

    @Test
    void fillsInlineStringPlaceholdersAndMergedBlankAnchorWithoutLosingStyles() throws Exception {
        byte[] template = inlineStringTemplate();
        Map<String, String> bindings = Map.of("xlsx:s0:r3:c1", "displayName");
        Map<String, String> values =
                Map.of(
                        "username", "qa_import_alice",
                        "enabled", "是",
                        "displayName", "张三",
                        "用户.姓名", "林小雨");

        byte[] rendered = DocumentTemplateProcessor.fillWorkbook(template, bindings, values);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("qa_import_alice", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("张三", sheet.getRow(3).getCell(1).getStringCellValue());
            assertEquals("是", sheet.getRow(4).getCell(1).getStringCellValue());
            assertEquals("张三", sheet.getRow(5).getCell(1).getStringCellValue());
            assertEquals("林小雨", sheet.getRow(7).getCell(1).getStringCellValue());
            assertEquals(6, sheet.getNumMergedRegions());
            assertEquals(
                    BorderStyle.THIN, sheet.getRow(3).getCell(1).getCellStyle().getBorderLeft());
            assertEquals(
                    BorderStyle.THIN, sheet.getRow(3).getCell(3).getCellStyle().getBorderRight());
        }
        String sheetXml = workbookPart(rendered, "xl/worksheets/sheet1.xml");
        String filledPlaceholder = cellXml(sheetXml, "B3");
        assertTrue(filledPlaceholder.contains("t=\"s\""));
        assertTrue(filledPlaceholder.contains("<v>"));
        assertFalse(filledPlaceholder.contains("<is>"));
    }

    @Test
    void keepsLocalWorkbookFormulasAndRejectsExternalOrDynamicFormulaFunctions() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> DocumentTemplateProcessor.validateWorkbookFormula("SUM(1,2)"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                        DocumentTemplateProcessor.validateWorkbookFormula(
                                "IF(A1=1,AVERAGE(B1:B4),0)"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                        DocumentTemplateProcessor.validateWorkbookFormula(
                                "\"WEBSERVICE(https://example.invalid)\""));

        for (String formula :
                List.of(
                        "WEBSERVICE(\"https://example.invalid\")",
                        "_xlfn.WEBSERVICE(\"https://example.invalid\")",
                        "HYPERLINK(\"https://example.invalid\")",
                        "DDE(\"application\",\"topic\",\"item\")",
                        "RTD(\"server\",\"topic\")",
                        "SQL.REQUEST(\"server\",\"query\")",
                        "FILTERXML(A1,\"//item\")")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    DocumentTemplateProcessor.InvalidTemplate.class,
                    () -> DocumentTemplateProcessor.validateWorkbookFormula(formula),
                    formula);
        }
    }

    @Test
    void rejectsOfficeArchivePartsThatCouldEscapeTheirPrivateWorkspace() throws Exception {
        byte[] archive;
        try (var output = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("../outside.xml"));
                zip.write("<x/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            archive = output.toByteArray();
        }

        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> DocumentTemplateProcessor.inspect("unsafe.docx", archive));
    }

    private static byte[] inlineStringTemplate() throws Exception {
        byte[] sharedStringWorkbook;
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("用户登记");
            var border = workbook.createCellStyle();
            border.setBorderTop(BorderStyle.THIN);
            border.setBorderBottom(BorderStyle.THIN);
            border.setBorderLeft(BorderStyle.THIN);
            border.setBorderRight(BorderStyle.THIN);

            setCell(sheet, 0, 0, "用户信息登记表 / User registration", border);
            setCell(sheet, 2, 0, "账号 / Account", border);
            setCell(sheet, 2, 1, "{{username}}", border);
            setCell(sheet, 2, 2, "", border);
            setCell(sheet, 2, 3, "", border);
            setCell(sheet, 3, 0, "姓名 / Name", border);
            setCell(sheet, 3, 1, "", border);
            setCell(sheet, 3, 2, "", border);
            setCell(sheet, 3, 3, "", border);
            setCell(sheet, 4, 0, "状态 / Status", border);
            setCell(sheet, 4, 1, "{{enabled}}", border);
            setCell(sheet, 4, 2, "", border);
            setCell(sheet, 4, 3, "", border);
            setCell(sheet, 5, 0, "备注 / Note", border);
            setCell(sheet, 5, 1, "{{displayName}}", border);
            setCell(sheet, 5, 2, "", border);
            setCell(sheet, 5, 3, "", border);
            setCell(sheet, 7, 0, "中文参数", border);
            setCell(sheet, 7, 1, "{{用户.姓名}}", border);
            setCell(sheet, 7, 2, "", border);
            setCell(sheet, 7, 3, "", border);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 3));
            sheet.addMergedRegion(new CellRangeAddress(3, 3, 1, 3));
            sheet.addMergedRegion(new CellRangeAddress(4, 4, 1, 3));
            sheet.addMergedRegion(new CellRangeAddress(5, 5, 1, 3));
            sheet.addMergedRegion(new CellRangeAddress(7, 7, 1, 3));
            workbook.setPrintArea(0, 0, 3, 0, 8);
            workbook.write(output);
            sharedStringWorkbook = output.toByteArray();
        }
        return replaceWithInlineStrings(
                sharedStringWorkbook,
                Map.ofEntries(
                        Map.entry("A1", "用户信息登记表 / User registration"),
                        Map.entry("A3", "账号 / Account"),
                        Map.entry("B3", "{{username}}"),
                        Map.entry("C3", ""),
                        Map.entry("D3", ""),
                        Map.entry("A4", "姓名 / Name"),
                        Map.entry("B4", ""),
                        Map.entry("C4", ""),
                        Map.entry("D4", ""),
                        Map.entry("A5", "状态 / Status"),
                        Map.entry("B5", "{{enabled}}"),
                        Map.entry("C5", ""),
                        Map.entry("D5", ""),
                        Map.entry("A6", "备注 / Note"),
                        Map.entry("B6", "{{displayName}}"),
                        Map.entry("C6", ""),
                        Map.entry("D6", ""),
                        Map.entry("A8", "中文参数"),
                        Map.entry("B8", "{{用户.姓名}}"),
                        Map.entry("C8", ""),
                        Map.entry("D8", "")));
    }

    private static void setCell(
            org.apache.poi.xssf.usermodel.XSSFSheet sheet,
            int rowIndex,
            int columnIndex,
            String value,
            org.apache.poi.ss.usermodel.CellStyle style) {
        var row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        var cell = row.createCell(columnIndex);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static byte[] replaceWithInlineStrings(byte[] source, Map<String, String> cells)
            throws IOException {
        try (var input = new ZipInputStream(new ByteArrayInputStream(source));
                var output = new ByteArrayOutputStream();
                var zip = new ZipOutputStream(output)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                byte[] content = input.readAllBytes();
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    String xml = new String(content, StandardCharsets.UTF_8);
                    for (var cell : cells.entrySet())
                        xml = inlineCell(xml, cell.getKey(), cell.getValue());
                    content = xml.getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private static String workbookPart(byte[] workbook, String requestedPart) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; )
                if (requestedPart.equals(entry.getName()))
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
        throw new AssertionError("Missing workbook part " + requestedPart);
    }

    private static String cellXml(String sheetXml, String reference) {
        var matcher =
                java.util.regex.Pattern.compile(
                                "<c\\b(?=[^>]*\\br=\""
                                        + java.util.regex.Pattern.quote(reference)
                                        + "\")([^>]*)(?:>.*?</c>|/>)")
                        .matcher(sheetXml);
        if (!matcher.find()) throw new AssertionError("Missing output cell " + reference);
        return matcher.group();
    }

    private static String inlineCell(String xml, String reference, String value) {
        var matcher =
                java.util.regex.Pattern.compile(
                                "<c\\b([^>]*)\\br=\""
                                        + java.util.regex.Pattern.quote(reference)
                                        + "\"([^>]*)>.*?</c>")
                        .matcher(xml);
        if (!matcher.find()) throw new AssertionError("Missing test cell " + reference);
        String attributes =
                (matcher.group(1) + "r=\"" + reference + "\"" + matcher.group(2))
                        .replace("t=\"s\"", "t=\"inlineStr\"");
        String inline = "<c" + attributes + "><is><t>" + escapeXml(value) + "</t></is></c>";
        return matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(inline));
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
