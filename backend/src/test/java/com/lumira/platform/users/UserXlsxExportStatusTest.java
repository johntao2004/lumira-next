package com.lumira.platform.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserXlsxExportStatusTest {
    private final UserXlsxExportService.Field status =
            new UserXlsxExportService.Field("enabled", "Status", "BOOLEAN", "account", true);

    @Test
    void activeAndDisabledCellsUseTheExactUiTranslations() {
        for (var pair :
                List.of(
                        List.of("正常", "停用"), List.of("Active", "Disabled"),
                        List.of("正常", "停用"), List.of("有効", "無効"),
                        List.of("활성", "비활성"), List.of("Actif", "Désactivé"),
                        List.of("Activo", "Desactivado"), List.of("Aktiv", "Deaktiviert"))) {
            var labels =
                    UserXlsxExportService.exportStatusLabels(
                            Map.of("true", pair.get(0), "false", pair.get(1)));
            assertEquals(
                    pair.get(0), UserXlsxExportService.exportCellValue(status, "true", labels));
            assertEquals(
                    pair.get(1), UserXlsxExportService.exportCellValue(status, "false", labels));
            assertEquals(true, UserXlsxImportService.parseBoolean(pair.get(0)));
            assertEquals(false, UserXlsxImportService.parseBoolean(pair.get(1)));
            assertEquals("", UserXlsxExportService.exportCellValue(status, null, labels));
            assertEquals("", UserXlsxExportService.exportCellValue(status, "", labels));
            assertEquals("", UserXlsxExportService.exportCellValue(status, "unknown", labels));
        }
    }

    @Test
    void otherFieldsRetainRealValues() {
        var labels =
                UserXlsxExportService.exportStatusLabels(
                        Map.of("true", "Active", "false", "Disabled"));
        assertEquals("Active", UserXlsxExportService.exportCellValue(status, "true", labels));
        assertEquals("Disabled", UserXlsxExportService.exportCellValue(status, "false", labels));
        var amount = new UserXlsxExportService.Field("amount", "Amount", "NUMBER", "custom", true);
        assertEquals("0", UserXlsxExportService.exportCellValue(amount, "0", labels));
        var text = new UserXlsxExportService.Field("note", "Note", "TEXT", "custom", true);
        assertEquals("false", UserXlsxExportService.exportCellValue(text, "false", labels));
        assertEquals(true, UserXlsxImportService.parseBoolean("true"));
        assertEquals(false, UserXlsxImportService.parseBoolean("false"));
    }

    @Test
    void invalidStatusLabelsAreRejectedBeforeBuildingTheWorkbook() {
        assertInvalid(null);
        for (String bad : List.of("", " ", "x".repeat(201), "Active\nInjected")) {
            assertInvalid(Map.of("true", bad, "false", "Disabled"));
        }
        assertInvalid(Map.of("true", "Active"));
        assertInvalid(Map.of("true", "Active", "false", "Disabled", "other", "Extra"));
        var nullLabel = new LinkedHashMap<String, String>();
        nullLabel.put("true", null);
        nullLabel.put("false", "Disabled");
        assertInvalid(nullLabel);
    }

    private static void assertInvalid(Map<String, String> labels) {
        assertEquals(
                400,
                assertThrows(
                                ResponseStatusException.class,
                                () -> UserXlsxExportService.exportStatusLabels(labels))
                        .getStatusCode()
                        .value());
    }
}
