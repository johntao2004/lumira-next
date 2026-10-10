package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class WatermarkController {
    public record View(long version, WatermarkSettings data) {}

    public record Input(long version, WatermarkSettings data, String changeReason) {}

    private final SettingDocuments documents;

    public WatermarkController(SettingDocuments documents) {
        this.documents = documents;
    }

    @GetMapping("/api/platform/settings/watermark")
    public View read() {
        var snapshot =
                documents.read("watermark", WatermarkSettings.class, WatermarkSettings::defaults);
        return new View(snapshot.version(), snapshot.data());
    }

    @GetMapping("/api/public/watermark")
    public WatermarkSettings published() {
        return read().data();
    }

    @PutMapping("/api/platform/settings/watermark")
    public View write(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidWatermark") @RequestBody Input input,
            Authentication auth) {
        var saved =
                documents.save(
                        "watermark",
                        input.version(),
                        input.data(),
                        input.changeReason(),
                        auth.getName(),
                        "invalidWatermark",
                        WatermarkSettings::validate);
        return new View(saved.version(), saved.data());
    }
}
