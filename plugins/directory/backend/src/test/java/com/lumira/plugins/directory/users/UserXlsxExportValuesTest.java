package com.lumira.plugins.directory.users;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.DocumentWorkerClient;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.TemplateUserDataPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserXlsxExportValuesTest {
    @Test
    void emptyAndZeroPlaceholderValuesStayBlankWithoutLosingMeaningfulZeros() {
        for (String type : List.of("TEXT", "TEXTAREA", "DATE", "NUMBER", "BOOLEAN", "SELECT")) {
            assertEquals("", value(type, null));
            assertEquals("", value(type, ""));
            assertEquals("", value(type, " \t\n"));
        }
        for (String type : List.of("TEXT", "TEXTAREA", "DATE")) {
            assertEquals("", value(type, "0"));
            assertEquals("", value(type, " 0 "));
        }
        assertEquals("0", value("NUMBER", "0"));
        assertEquals("0.00", value("NUMBER", "0.00"));
        assertEquals("false", value("BOOLEAN", "false"));
        assertEquals("true", value("BOOLEAN", "true"));
        assertEquals("0", value("SELECT", "0"));
        assertEquals("0010", value("TEXT", "0010"));
        assertEquals("2026-10", value("DATE", "2026-10"));
    }

    @Test
    void actualExportPassesBlankCellsAndRealValuesToWorkbookWriterInSelectedOrder() {
        var id = UUID.randomUUID();
        var user = new UserSummary(id, "fixture", "Fixture", false, 1, null, null);
        var repository = mock(UserRepository.class);
        when(repository.findSelected(
                        Set.of(id),
                        "admin",
                        null,
                        PlatformOperation.USER_EXPORT.permission(),
                        PlatformPageId.USERS.accessPermission()))
                .thenReturn(List.of(user));
        var data = mock(TemplateUserDataPort.class);
        var fields =
                List.of(
                        field("realName", "TEXT"), field("birthDate", "DATE"),
                        field("region", "TEXT"), field("missing", "TEXT"),
                        field("blank", "TEXTAREA"), field("amount", "NUMBER"),
                        field("enabled", "BOOLEAN"), field("choice", "SELECT"));
        when(data.readableFields("admin")).thenReturn(fields);
        var keys = fields.stream().map(TemplateUserDataPort.Field::key).toList();
        var values = new HashMap<String, String>();
        values.put("realName", "0");
        values.put("birthDate", "0");
        values.put("region", null);
        values.put("blank", "   ");
        values.put("amount", "0");
        values.put("enabled", "false");
        values.put("choice", "0");
        when(data.readValuesBatch(List.of(id), "admin", Set.copyOf(keys)))
                .thenReturn(Map.of(id, values));
        var service =
                new UserXlsxExportService(
                        mock(AdministrationPort.class),
                        repository,
                        data,
                        new UserXlsxExportLocalization(
                                repository, new tools.jackson.databind.ObjectMapper()));
        var request =
                new UserXlsxExportService.Request(
                        "selected",
                        1,
                        10,
                        List.of(id),
                        "",
                        Map.of(),
                        Map.of(),
                        keys,
                        keys.stream()
                                .collect(java.util.stream.Collectors.toMap(key -> key, key -> key)),
                        Map.of("true", "正常", "false", "停用"));
        var rows = List.of(keys, List.of("", "", "", "", "", "0", "停用", "0"));
        byte[] workbook = {80, 75};
        try (var worker = mockStatic(DocumentWorkerClient.class)) {
            worker.when(() -> DocumentWorkerClient.writeWorkbook(rows)).thenReturn(workbook);
            assertArrayEquals(workbook, service.export(request, "admin", null));
            worker.verify(() -> DocumentWorkerClient.writeWorkbook(rows));
        }
    }

    private static String value(String type, String value) {
        return UserXlsxExportService.exportCellValue(
                new UserXlsxExportService.Field("field", "Field", type, "profile", true), value);
    }

    private static TemplateUserDataPort.Field field(String key, String type) {
        return new TemplateUserDataPort.Field(key, key, type, "profile", true, false);
    }
}
