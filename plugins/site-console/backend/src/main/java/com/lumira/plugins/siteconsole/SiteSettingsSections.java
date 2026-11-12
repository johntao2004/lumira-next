package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.PlatformOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The administration owner maps only its own sections to their existing operation codes. */
final class SiteSettingsSections {
    static PlatformOperation operation(String section) {
        if (section == null) throw missing();
        return switch (section) {
            case "branding" -> PlatformOperation.BRANDING_UPDATE;
            case "watermark" -> PlatformOperation.WATERMARK_UPDATE;
            case "agreements" -> PlatformOperation.AGREEMENTS_UPDATE;
            case "maintenance" -> PlatformOperation.MAINTENANCE_UPDATE;
            default -> throw missing();
        };
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
    }

    private SiteSettingsSections() {}
}
