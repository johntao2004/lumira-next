package com.lumira.platform.identity;

import com.lumira.platform.common.SecretKeys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Numeric SMS codes, keyed per account; existing long tokens remain valid until expiry. */
@Component
public final class PhoneVerificationCode {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeys keys;

    public PhoneVerificationCode(SecretKeys keys) {
        this.keys = keys;
    }

    public VerificationToken.Issued issue(UUID owner) {
        String code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
        return new VerificationToken.Issued(
                code,
                keys.require()
                        .authenticate("phone:" + owner, code.getBytes(StandardCharsets.US_ASCII)));
    }

    public boolean matches(UUID owner, String code, String digest) {
        if (code == null) return false;
        if (code.matches("[A-Za-z0-9_-]{43}"))
            return !VerificationToken.digest(code).isEmpty()
                    && VerificationToken.digest(code).equals(digest);
        if (!code.matches("\\d{6}")) return false;
        return keys.require()
                .matchesAuthentication(
                        "phone:" + owner, code.getBytes(StandardCharsets.US_ASCII), digest);
    }
}
