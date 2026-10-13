package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PluginDataRemovalValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void restoresLegacyFeedbackManifestWithoutRetiredDraftScope() throws Exception {
        var manifest =
                mapper.readValue(
                        """
                        {
                          "formatVersion": 1,
                          "id": "feedback",
                          "name": "工单反馈",
                          "version": "1.0.6",
                          "sdkVersion": "1",
                          "entryClass": "com.lumira.platform.feedback.LumiraPlugin",
                          "backend": "backend/plugin.jar",
                          "frontend": "frontend/plugin.js",
                          "dependencies": [],
                          "permissions": [],
                          "routes": [],
                          "apiRules": [],
                          "files": {},
                          "dataSets": [
                            {"table": "platform_feedback_file", "label": "pluginDataFeedbackFiles", "column": null, "value": null},
                            {"table": "platform_feedback", "label": "pluginDataFeedback", "column": null, "value": null},
                            {"table": "platform_account_draft", "label": "pluginDataFeedbackDrafts", "column": "draft_key", "value": "feedback.create"}
                          ]
                        }
                        """,
                        PluginManifest.class);

        assertEquals(
                List.of(
                        new PluginManifest.DataSet(
                                "platform_feedback_file", "pluginDataFeedbackFiles", null, null),
                        new PluginManifest.DataSet(
                                "platform_feedback", "pluginDataFeedback", null, null)),
                manifest.dataSets());
    }

    @Test
    void rejectsUnmatchedOrNonFeedbackDraftCleanupScopes() {
        var legacyFeedbackDraft =
                new PluginManifest.DataSet(
                        "platform_account_draft", "drafts", "draft_key", "feedback.create");
        var anotherDraftKey =
                new PluginManifest.DataSet(
                        "platform_account_draft", "drafts", "draft_key", "other.create");

        assertThrows(
                IllegalArgumentException.class,
                () -> PluginDataRemoval.validate("feedback", List.of(legacyFeedbackDraft)));
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginDataRemoval.validate("feedback", List.of(anotherDraftKey)));
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginDataRemoval.validate("localization", List.of(legacyFeedbackDraft)));
    }
}
