package com.lumira.platform.directory.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lumira.platform.common.DocumentWorkerClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemplateRenderInputTest {
    @Test
    void placeholderRemappingCannotOverwriteAHandBoundSlotValue() {
        var inspection =
                new DocumentWorkerClient.Inspection(
                        List.of(
                                new DocumentWorkerClient.Slot(
                                        "manual-name", "Name", "____________")),
                        List.of("displayName"));

        var input =
                TemplateService.renderInput(
                        inspection,
                        Map.of("manual-name", "displayName", "displayName", "username"),
                        Map.of("displayName", "Ada Lovelace", "username", "ada"),
                        Set.of("displayName", "username"));

        assertEquals(Map.of("manual-name", "slotValue0"), input.slotBindings());
        assertEquals("Ada Lovelace", input.values().get("slotValue0"));
        assertEquals("ada", input.values().get("displayName"));
    }

    @Test
    void generatedSlotAliasesSkipPlaceholderNames() {
        var inspection =
                new DocumentWorkerClient.Inspection(
                        List.of(
                                new DocumentWorkerClient.Slot(
                                        "manual-name", "Name", "____________")),
                        List.of("slotValue0"));

        var input =
                TemplateService.renderInput(
                        inspection,
                        Map.of("manual-name", "displayName"),
                        Map.of("displayName", "Ada Lovelace", "slotValue0", "custom"),
                        Set.of("displayName", "slotValue0"));

        assertEquals(Map.of("manual-name", "slotValue1"), input.slotBindings());
        assertEquals("Ada Lovelace", input.values().get("slotValue1"));
        assertEquals("custom", input.values().get("slotValue0"));
    }
}
