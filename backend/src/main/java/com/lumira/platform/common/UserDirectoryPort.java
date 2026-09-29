package com.lumira.platform.common;

import java.util.UUID;

/** Minimal user contract used by platform modules for optimistic writes. */
public interface UserDirectoryPort {
    long version(UUID userId);

    /** Reads the version for a read-only snapshot without taking a row lock. */
    long versionSnapshot(UUID userId);
}
