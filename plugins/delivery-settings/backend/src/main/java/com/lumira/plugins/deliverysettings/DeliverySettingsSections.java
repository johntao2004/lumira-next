package com.lumira.plugins.deliverysettings;

import com.lumira.platform.common.PlatformOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The administration owner maps only its own sections to their existing operation codes. */
final class DeliverySettingsSections {
    static PlatformOperation operation(String section) {
        if (section == null) throw missing();
        return switch (section) {
            case "smtp" -> PlatformOperation.SMTP_UPDATE;
            case "sms" -> PlatformOperation.SMS_UPDATE;
            default -> throw missing();
        };
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
    }

    private DeliverySettingsSections() {}
}
