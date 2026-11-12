package com.lumira.platform.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Persisted storage retention policy; administration is an optional plugin. */
public record FileRetentionSettings(Mode mode, int retentionDays) {
    public enum Mode {
        AUTOMATIC,
        MANUAL
    }

    public static FileRetentionSettings defaults() {
        return new FileRetentionSettings(Mode.AUTOMATIC, 30);
    }

    public void validate() {
        if (mode == null || retentionDays < 1 || retentionDays > 365)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFileRetention");
    }
}
