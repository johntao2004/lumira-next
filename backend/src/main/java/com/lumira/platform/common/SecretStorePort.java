package com.lumira.platform.common;

import java.util.Optional;

/** Internal secret-storage boundary for modules that consume encrypted credentials. */
public interface SecretStorePort {
    record State(long version, boolean configured) {}

    State state(String context);

    /** Returns owned plaintext bytes for short-lived use; the caller should clear them. */
    Optional<byte[]> reveal(String context);

    /** Authorization and audit remain the responsibility of the typed caller. */
    State save(String context, long expectedVersion, byte[] plaintext);
}
