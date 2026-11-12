package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

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
        var provider =
                new IdentityConfiguration()
                        .authenticationProvider(
                                username -> stored,
                                encoder(),
                                (user, newPassword) -> {
                                    upgraded.set(newPassword);
                                    return User.withUserDetails(user).password(newPassword).build();
                                });

        var authentication =
                provider.authenticate(new UsernamePasswordAuthenticationToken("legacy", password));

        assertTrue(authentication.isAuthenticated());
        assertNotNull(upgraded.get());
        assertTrue(upgraded.get().startsWith("{argon2id@lumira_v1}$argon2id$"));
    }

    @Test
    void providerManagerErasesPasswordButRetainsOnlyRequestBoundFingerprintAndObservedEpoch() {
        var encoder = encoder();
        var storedHash = encoder.encode("correct horse battery");
        var revision = UUID.randomUUID();
        var user =
                new PasswordProofUserDetails(
                        "alice",
                        storedHash,
                        true,
                        revision,
                        List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("platform:users:read")));
        var proofs = new PasswordCredentialProof();
        var provider =
                new IdentityConfiguration()
                        .authenticationProvider(
                                username -> user,
                                encoder,
                                (details, newPassword) ->
                                        new PasswordProofUserDetails(
                                                details.getUsername(),
                                                newPassword,
                                                details.isEnabled(),
                                                ((PasswordProofUserDetails) details)
                                                        .primaryCredentialRevision(),
                                                details.getAuthorities()),
                                proofs);
        var manager = new ProviderManager(List.of(provider));
        assertTrue(manager.isEraseCredentialsAfterAuthentication());

        var authentication =
                manager.authenticate(
                        new UsernamePasswordAuthenticationToken("alice", "correct horse battery"));

        assertTrue(authentication.isAuthenticated());
        assertNull(
                ((org.springframework.security.core.userdetails.UserDetails)
                                authentication.getPrincipal())
                        .getPassword());
        assertInstanceOf(PasswordCredentialProof.Evidence.class, authentication.getDetails());
        var evidence = (PasswordCredentialProof.Evidence) authentication.getDetails();
        assertNotEquals(storedHash, evidence.hashFingerprint());
        assertTrue(proofs.matches(evidence.hashFingerprint(), storedHash));
        assertEquals(revision, evidence.primaryCredentialRevision());
    }

    @Test
    void legacyBcryptUpgradeFingerprintsTheHashNowStoredOnTheSamePrincipal() {
        var password = "legacy-password-123";
        var legacy = new BCryptPasswordEncoder(4).encode(password);
        var revision = UUID.randomUUID();
        var user = new PasswordProofUserDetails("legacy", legacy, true, revision, List.of());
        var proofs = new PasswordCredentialProof();
        var upgraded = new AtomicReference<String>();
        var provider =
                new IdentityConfiguration()
                        .authenticationProvider(
                                username -> user,
                                encoder(),
                                (details, newPassword) -> {
                                    upgraded.set(newPassword);
                                    if (details instanceof PasswordProofUserDetails proofUser) {
                                        proofUser.useEncodedPassword(newPassword);
                                        return proofUser;
                                    }
                                    return User.withUserDetails(details)
                                            .password(newPassword)
                                            .build();
                                },
                                proofs);
        var manager = new ProviderManager(List.of(provider));

        var authentication =
                manager.authenticate(new UsernamePasswordAuthenticationToken("legacy", password));

        var evidence = (PasswordCredentialProof.Evidence) authentication.getDetails();
        assertTrue(upgraded.get().startsWith("{argon2id@lumira_v1}$argon2id$"));
        assertTrue(proofs.matches(evidence.hashFingerprint(), upgraded.get()));
        assertFalse(proofs.matches(evidence.hashFingerprint(), legacy));
        assertEquals(revision, evidence.primaryCredentialRevision());
    }
}
