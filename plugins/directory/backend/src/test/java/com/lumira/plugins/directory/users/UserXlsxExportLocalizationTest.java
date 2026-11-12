package com.lumira.plugins.directory.users;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.DocumentWorkerClient;
import com.lumira.platform.common.TemplateUserDataPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class UserXlsxExportLocalizationTest {
    @Test
    void legacyRequestsUseSavedUiLanguageForWorkbookHeadersAndStatus() {
        var users = mock(UserRepository.class);
        var data = mock(TemplateUserDataPort.class);
        var localization = new UserXlsxExportLocalization(users, new ObjectMapper());
        var service =
                new UserXlsxExportService(
                        mock(AdministrationPort.class), users, data, localization);
        var id = UUID.randomUUID();
        var keys =
                List.of(
                        "username",
                        "displayName",
                        "enabled",
                        "realName",
                        "birthDate",
                        "region",
                        "roles",
                        "organizations",
                        "custom",
                        "renamed");
        var fields =
                List.of(
                        field("username", "Username", "account"),
                        field("displayName", "Display name", "account"),
                        new TemplateUserDataPort.Field(
                                "enabled", "Enabled", "BOOLEAN", "account", true, false),
                        field("realName", "Real name", "profile"),
                        field("birthDate", "Birth date", "profile"),
                        field("region", "Region", "profile"),
                        field("roles", "Roles", "roles"),
                        field("organizations", "Organizations", "organizations"),
                        field("custom", "Employee code", "custom"),
                        field("renamed", "Office location", "profile"));
        when(data.readableFields("admin")).thenReturn(fields);
        when(users.findSelected(anySet(), eq("admin"), isNull(), anyString(), anyString()))
                .thenReturn(List.of(new UserSummary(id, "person", "Person", false, 1, null, null)));
        when(data.readValuesBatch(List.of(id), "admin", Set.copyOf(keys)))
                .thenReturn(Map.of(id, Map.of("enabled", "false")));
        var request =
                new UserXlsxExportService.Request(
                        "selected", 1, 10, List.of(id), "", Map.of(), Map.of(), keys, null, null);
        for (String locale : List.of("zh-CN", "en")) {
            when(users.preferredLocale("admin")).thenReturn(locale);
            var headers =
                    locale.equals("zh-CN")
                            ? List.of(
                                    "用户名",
                                    "名称",
                                    "状态",
                                    "姓名",
                                    "出生月份",
                                    "地区",
                                    "角色",
                                    "组织",
                                    "Employee code",
                                    "Office location")
                            : List.of(
                                    "Username",
                                    "Name",
                                    "Status",
                                    "Full name",
                                    "Birth month",
                                    "Region",
                                    "Roles",
                                    "Organizations",
                                    "Employee code",
                                    "Office location");
            var row =
                    List.of(
                            "",
                            "",
                            locale.equals("zh-CN") ? "停用" : "Disabled",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "");
            var workbookRows = List.of(headers, row);
            byte[] workbook = {80, 75};
            try (var worker = mockStatic(DocumentWorkerClient.class)) {
                worker.when(() -> DocumentWorkerClient.writeWorkbook(workbookRows))
                        .thenReturn(workbook);
                assertArrayEquals(workbook, service.export(request, "admin", null));
                worker.verify(() -> DocumentWorkerClient.writeWorkbook(workbookRows));
            }
        }
    }

    @Test
    void unsetLocaleUsesUiDefaultAndRenamedBuiltinsAndCustomLabelsRemainUnchanged() {
        var users = mock(UserRepository.class);
        var localization = new UserXlsxExportLocalization(users, new ObjectMapper());
        var messages = localization.messages("admin");
        assertEquals("状态", messages.get("status"));
        var renamed =
                new UserXlsxExportService.Field(
                        "realName", "Preferred name", "TEXT", "profile", true);
        var custom =
                new UserXlsxExportService.Field(
                        "region", "Regional office", "TEXT", "custom", true);
        assertEquals(
                Map.of("realName", "Preferred name", "region", "Regional office"),
                localization.fieldLabels(
                        List.of("realName", "region"),
                        Map.of("realName", renamed, "region", custom),
                        messages));
    }

    private static TemplateUserDataPort.Field field(String key, String label, String source) {
        return new TemplateUserDataPort.Field(key, label, "TEXT", source, true, false);
    }
}
