package com.lumira.platform.common;

/** Reads the current system-wide file quota defaults for runtime policies and API views. */
public interface FileQuotaDefaultsPort {
    FileQuotaPolicyPort.Limits current();
}
