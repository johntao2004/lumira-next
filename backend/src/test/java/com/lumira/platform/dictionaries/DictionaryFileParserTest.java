package com.lumira.platform.dictionaries;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DictionaryFileParserTest {
    @Test
    void returnsOriginalLineNumbersAndCapsDiagnostics() {
        var parser = new DictionaryFileParser();
        var error =
                assertThrows(
                        com.lumira.platform.common.ImportValidationException.class,
                        () ->
                                parser.parse(
                                        "itemValue\titemLabel\n\nbad\t\nother\t\n"
                                                .getBytes(StandardCharsets.UTF_8),
                                        "rows.txt",
                                        "FLAT"));
        assertEquals(java.util.List.of(3, 4), error.issues().stream().map(x -> x.row()).toList());
        assertTrue(error.issues().stream().allMatch(x -> x.field().equals("itemLabel")));
        var many = "itemValue\titemLabel\n" + "bad\t\n".repeat(150);
        var capped =
                assertThrows(
                        com.lumira.platform.common.ImportValidationException.class,
                        () ->
                                parser.parse(
                                        many.getBytes(StandardCharsets.UTF_8), "rows.txt", "FLAT"));
        assertEquals(100, capped.issues().size());
    }

    private final DictionaryFileParser parser = new DictionaryFileParser();
    private final String text =
            "itemValue\titemLabel\tparentItemValue\tlevelNo\tsortNo\tstatus\tremark\tleaf\nchild\tChild\troot\t2\t7\tDISABLED\tnote\ttrue\nroot\tRoot\t\t1\t2\tENABLED\t\tfalse\n";

    @Test
    void textPreservesLegacyHierarchyAndHash() {
        var result =
                parser.parse(
                        ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8), "tree.TXT", "TREE");
        assertEquals(2, result.items().size());
        assertEquals("root", result.items().getFirst().parentValue());
        assertFalse(result.items().getFirst().enabled());
        assertEquals(7, result.items().getFirst().sortOrder());
        assertEquals(64, result.sha256().length());
        assertNotEquals(
                result.sha256(),
                parser.parse(text.getBytes(StandardCharsets.UTF_8), "tree.txt", "TREE").sha256());
    }

    @Test
    void bothExcelFormatsMatchText() throws Exception {
        var expected =
                parser.parse(text.getBytes(StandardCharsets.UTF_8), "tree.txt", "TREE").items();
        for (String extension : new String[] {"xls", "xlsx"}) {
            try (Workbook workbook =
                            extension.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook();
                    var bytes = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet();
                int i = 0;
                for (String line : text.split("\n")) {
                    var row = sheet.createRow(i++);
                    int j = 0;
                    for (String cell : line.split("\t", -1)) row.createCell(j++).setCellValue(cell);
                }
                workbook.write(bytes);
                assertEquals(
                        expected,
                        parser.parse(bytes.toByteArray(), "tree." + extension, "TREE").items());
                // Reuse these verified real workbooks in the browser import suite.
                var fixtures =
                        java.nio.file.Path.of(
                                System.getProperty("basedir", "."), "target", "browser-fixtures");
                java.nio.file.Files.createDirectories(fixtures);
                java.nio.file.Files.write(
                        fixtures.resolve("tree." + extension), bytes.toByteArray());
            }
        }
    }

    @Test
    void rejectsBrokenFilesHierarchyAndDuplicates() {
        for (String bad :
                new String[] {
                    text.replace("root\tRoot", "child\tRoot"),
                    text.replace("\tfalse", "\ttrue"),
                    text.replace("\t2\t7", "\t3\t7"),
                    text.replace("itemLabel", "itemValue"),
                    text.replace("DISABLED", "UNKNOWN")
                })
            assertThrows(
                    ResponseStatusException.class,
                    () -> parser.parse(bad.getBytes(StandardCharsets.UTF_8), "bad.txt", "TREE"));
        assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(new byte[] {(byte) 0xff}, "bad.txt", "FLAT"));
        assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(new byte[] {1}, "bad.xlsx", "FLAT"));
        assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(text.getBytes(StandardCharsets.UTF_8), "bad.csv", "FLAT"));
    }

    @Test
    void legacyFlatIgnoresHierarchyAndLimitsBytes() {
        assertNull(
                parser.parse(text.getBytes(StandardCharsets.UTF_8), "flat.txt", "FLAT")
                        .items()
                        .getFirst()
                        .parentValue());
        assertEquals(
                413,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        parser.parse(
                                                new byte[DictionaryFileParser.MAX_BYTES + 1],
                                                "large.txt",
                                                "FLAT"))
                        .getStatusCode()
                        .value());
    }

    @Test
    void rejectsWorkbookDeclaringAbsurdColumnCount() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
                var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("itemValue");
            header.createCell(1).setCellValue("itemLabel");
            header.createCell(DictionaryFileParser.MAX_COLUMNS + 100).setCellValue("x");
            workbook.write(bytes);
            assertThrows(
                    ResponseStatusException.class,
                    () -> parser.parse(bytes.toByteArray(), "wide.xlsx", "FLAT"));
        }
    }
}
