package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentWorkerRequestGuardTest {
    @Test
    void acceptsBoundedDocumentMetadataAndWorkbookRows() {
        assertDoesNotThrow(
                () ->
                        DocumentWorkerRequestGuard.validate(
                                bytes(
                                        "{\"bindings\":{\"word:p0\":\"name\"},"
                                                + "\"values\":{\"name\":\"林\","
                                                + "\"用户.姓名\":\"周\"}}"),
                                OfficeWorkerProtocol.DOCUMENT_PDF));
        assertDoesNotThrow(
                () ->
                        DocumentWorkerRequestGuard.validate(
                                bytes("{\"rows\":[[\"姓名\",\"值\"],[\"林\"]]}"),
                                OfficeWorkerProtocol.WORKBOOK_WRITE));
    }

    @Test
    void rejectsDuplicateFieldsUnexpectedShapesAndNonStringCells() {
        assertThrows(
                java.io.IOException.class,
                () ->
                        DocumentWorkerRequestGuard.validate(
                                bytes("{\"bindings\":{},\"bindings\":{},\"values\":{}}"),
                                OfficeWorkerProtocol.DOCUMENT_PDF));
        assertThrows(
                java.io.IOException.class,
                () ->
                        DocumentWorkerRequestGuard.validate(
                                bytes("{\"rows\":[[1]]}"), OfficeWorkerProtocol.WORKBOOK_WRITE));
        assertThrows(
                java.io.IOException.class,
                () ->
                        DocumentWorkerRequestGuard.validate(
                                bytes("{\"rows\":[[\"x\"]],\"extra\":true}"),
                                OfficeWorkerProtocol.WORKBOOK_WRITE));
    }

    @Test
    void enforcesWorkbookRowAndColumnCapsBeforeBindingJson() {
        StringBuilder row = new StringBuilder("{\"rows\":[[");
        for (int index = 0; index <= DocumentTemplateProcessor.MAX_WORKBOOK_COLUMNS; index++) {
            if (index > 0) row.append(',');
            row.append("\"x\"");
        }
        row.append("]]}");
        assertThrows(
                java.io.IOException.class,
                () ->
                        DocumentWorkerRequestGuard.validate(
                                bytes(row.toString()), OfficeWorkerProtocol.WORKBOOK_WRITE));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
