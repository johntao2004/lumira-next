package com.lumira.plugins.directory.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserXlsxExportHeadersTest {
    private final List<String> selected = List.of("displayName", "enabled", "customField");
    private final Map<String, UserXlsxExportService.Field> catalog =
            Map.of(
                    "displayName", field("displayName", "Display name"),
                    "enabled", field("enabled", "Enabled"),
                    "customField", field("customField", "Employee code"));

    @Test
    void usesExactUiLabelsInSelectedOrderForEveryLanguageIncludingCustomLabels() {
        for (var headers :
                List.of(
                        List.of("名称", "状态", "员工编号"),
                        List.of("Name", "Status", "Employee code"),
                        List.of("名称", "狀態", "員工編號"),
                        List.of("名前", "状態", "社員番号"),
                        List.of("이름", "상태", "직원 번호"),
                        List.of("Nom", "Statut", "Code employé"),
                        List.of("Nombre", "Estado", "Código de empleado"),
                        List.of("Name", "Status", "Mitarbeiternummer"))) {
            var labels =
                    Map.of(
                            "customField",
                            headers.get(2),
                            "enabled",
                            headers.get(1),
                            "displayName",
                            headers.get(0));
            assertEquals(headers, UserXlsxExportService.exportHeaders(selected, catalog, labels));
        }
    }

    @Test
    void missingLabelsMustBeResolvedBeforeValidation() {
        assertInvalid(null);
    }

    @Test
    void rejectsMissingExtraBlankOversizedNullAndControlCharacterLabels() {
        for (String invalid : List.of("", " ", "x".repeat(201), "Name\nInjected", "Name\u0000")) {
            var labels =
                    new LinkedHashMap<>(
                            Map.of(
                                    "displayName",
                                    "Name",
                                    "enabled",
                                    "Status",
                                    "customField",
                                    invalid));
            assertInvalid(labels);
        }
        var labels =
                new LinkedHashMap<>(
                        Map.of("displayName", "Name", "enabled", "Status", "customField", "Code"));
        labels.put("enabled", null);
        assertInvalid(labels);
        assertInvalid(Map.of("displayName", "Name"));
        assertInvalid(
                Map.of(
                        "displayName",
                        "Name",
                        "enabled",
                        "Status",
                        "customField",
                        "Code",
                        "password",
                        "Secret"));
    }

    private void assertInvalid(Map<String, String> labels) {
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> UserXlsxExportService.exportHeaders(selected, catalog, labels));
        assertEquals(400, error.getStatusCode().value());
    }

    private static UserXlsxExportService.Field field(String key, String label) {
        return new UserXlsxExportService.Field(key, label, "TEXT", "account", true);
    }
}
