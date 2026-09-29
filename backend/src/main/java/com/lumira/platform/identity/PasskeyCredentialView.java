package com.lumira.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Deliberately omits credential IDs, public keys and counters from management responses. */
public record PasskeyCredentialView(UUID id, String label, OffsetDateTime createdAt,
                                    OffsetDateTime lastUsedAt) {}
