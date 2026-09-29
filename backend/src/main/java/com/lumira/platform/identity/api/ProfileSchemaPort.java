package com.lumira.platform.identity.api;

/** Versioned profile schema supplied by the platform settings adapter. */
public interface ProfileSchemaPort {
    Snapshot read();

    record Snapshot(long version, ProfileFields data) {}
}
