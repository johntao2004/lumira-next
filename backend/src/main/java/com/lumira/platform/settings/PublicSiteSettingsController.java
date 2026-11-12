package com.lumira.platform.settings;

import java.time.Instant;
import org.springframework.web.bind.annotation.*;

/** Published values remain available to the shell and authentication while editors are absent. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class PublicSiteSettingsController {
    public record MaintenanceStatus(boolean active, String title, String message, Instant endAt) {}

    private final SettingDocuments documents;
    private final MaintenanceReader maintenance;

    public PublicSiteSettingsController(SettingDocuments documents, MaintenanceReader maintenance) {
        this.documents = documents;
        this.maintenance = maintenance;
    }

    @GetMapping("/api/public/agreements")
    public Agreements agreements() {
        return documents.read("agreements", Agreements.class, Agreements::defaults).data();
    }

    @GetMapping("/api/public/watermark")
    public WatermarkSettings watermark() {
        return documents
                .read("watermark", WatermarkSettings.class, WatermarkSettings::defaults)
                .data();
    }

    @GetMapping("/api/public/maintenance")
    public MaintenanceStatus maintenance() {
        var data = maintenance.read().data();
        return new MaintenanceStatus(
                data.activeAt(Instant.now()), data.title(), data.message(), data.endAt());
    }
}
