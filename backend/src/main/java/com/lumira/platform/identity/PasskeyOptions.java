package com.lumira.platform.identity;

import java.util.Map;

/** Browser-facing WebAuthn options. The challenge identifier is opaque to clients. */
public record PasskeyOptions(String challengeId, Map<String, Object> publicKey) {}
