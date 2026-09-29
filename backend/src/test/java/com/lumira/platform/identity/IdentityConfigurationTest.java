package com.lumira.platform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.User;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IdentityConfigurationTest {
    private PasswordEncoder encoder() {
        return new IdentityConfiguration().passwordEncoder();
    }

    @Test
    void newHashesUseVersionedArgon2idAndDoNotNeedAnUpgrade() {
        var encoder = encoder();
        var password = "A modern password with unicode 密";

        var encoded = encoder.encode(password);

        assertTrue(encoded.startsWith("{argon2id@lumira_v1}$argon2id$"));
        assertTrue(encoder.matches(password, encoded));
        assertFalse(encoder.upgradeEncoding(encoded));
    }

    @Test
    void legacyRawAndTaggedBcryptHashesRemainReadableAndAreMarkedForUpgrade() {
        var password = "legacy-password-123";
        var legacy = new BCryptPasswordEncoder(4).encode(password);
        var encoder = encoder();

        assertTrue(encoder.matches(password, legacy));
        assertTrue(encoder.matches(password, "{bcrypt}" + legacy));
        assertTrue(encoder.upgradeEncoding(legacy));
        assertTrue(encoder.upgradeEncoding("{bcrypt}" + legacy));
        assertFalse(encoder.matches("wrong-password", legacy));
    }

    @Test
    void successfulLegacyAuthenticationInvokesTheAutomaticUpgradeHook() {
        var password = "legacy-password-123";
        var legacy = new BCryptPasswordEncoder(4).encode(password);
        var stored = User.withUsername("legacy").password(legacy).roles("USER").build();
        var upgraded = new AtomicReference<String>();
        var provider = new IdentityConfiguration().authenticationProvider(
                username -> stored,
                encoder(),
                (user, newPassword) -> {
                    upgraded.set(newPassword);
                    return User.withUserDetails(user).password(newPassword).build();
                });

        var authentication = provider.authenticate(
                new UsernamePasswordAuthenticationToken("legacy", password));

        assertTrue(authentication.isAuthenticated());
        assertNotNull(upgraded.get());
        assertTrue(upgraded.get().startsWith("{argon2id@lumira_v1}$argon2id$"));
    }
}
