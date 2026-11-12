package com.lumira.plugins.fileconsole;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.FileQuotaSettings;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("file-console")
public class FileQuotaSettingsController {
    private final FileQuotaSettingsService settings;

    public FileQuotaSettingsController(FileQuotaSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/platform/settings/file-quota")
    public FileQuotaSettingsService.View read(Authentication actor) {
        return settings.read(actor.getName());
    }

    @PutMapping("/api/platform/settings/file-quota")
    public FileQuotaSettingsService.View save(
            @BoundedBody(maxBytes = 4096, errorCode = "invalidFileQuota") @RequestBody
                    JsonNode input,
            Authentication actor) {
        if (input == null || !input.isObject()) throw invalidFileQuota();
        long version = nonNegativeSafeInteger(input.get("version"));
        JsonNode data = input.get("data");
        if (data == null || !data.isObject()) throw invalidFileQuota();
        var settings =
                new FileQuotaSettings(
                        nonNegativeSafeInteger(data.get("maximumBytes")),
                        nonNegativeSafeInteger(data.get("maximumFiles")));
        return this.settings.save(version, settings, actor.getName());
    }

    private static long nonNegativeSafeInteger(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw invalidFileQuota();
        long number = value.longValue();
        if (number < 0 || number > FileQuotaSettings.MAX_SAFE_INTEGER) throw invalidFileQuota();
        return number;
    }

    private static ResponseStatusException invalidFileQuota() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFileQuota");
    }
}
