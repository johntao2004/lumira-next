package com.lumira.platform.ai;

import com.lumira.platform.common.SecretStorePort;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads an AI destination and its secret in one short, consistent database snapshot. */
@Component
public final class AiConnectionSnapshot {
    public static final class Captured implements AutoCloseable {
        private final AiProviderProfile profile;
        private final byte[] credential;

        private Captured(AiProviderProfile profile, byte[] credential) {
            this.profile = profile;
            this.credential = credential;
        }

        public AiProviderProfile profile() {
            return profile;
        }

        public byte[] credential() {
            return credential;
        }

        @Override
        public void close() {
            if (credential != null) Arrays.fill(credential, (byte) 0);
        }
    }

    private final AiProviderRepository providers;
    private final SecretStorePort secrets;
    private final TransactionTemplate transaction;

    public AiConnectionSnapshot(
            AiProviderRepository providers,
            SecretStorePort secrets,
            PlatformTransactionManager manager) {
        this.providers = providers;
        this.secrets = secrets;
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Captured capture(AiProviderProfile requested) {
        return capture(requested, null, false);
    }

    /** Allows an unsaved model-discovery destination only when the caller provides its key. */
    public Captured capture(
            AiProviderProfile requested, byte[] credentialOverride, boolean allowMissingProvider) {
        AiProviderProfile[] selected = new AiProviderProfile[1];
        byte[][] owned = new byte[1][];
        try {
            transaction.executeWithoutResult(
                    status -> {
                        var stored = providers.find(requested.id());
                        if (stored.isEmpty()) {
                            // Only a transient discovery profile has a blank model name. A saved
                            // provider can disappear between the controller read and this
                            // snapshot; it must not turn into a caller-controlled destination.
                            if (!allowMissingProvider || !requested.modelName().isBlank()) stale();
                            selected[0] = requested;
                            owned[0] = copy(credentialOverride);
                            return;
                        }
                        AiProviderProfile current = stored.get();
                        if (!sameVersionedConnection(requested, current)) stale();
                        selected[0] = current;
                        owned[0] =
                                credentialOverride == null
                                        ? secrets.reveal(
                                                        ModelConnectionService.secretContext(
                                                                current.id()))
                                                .orElse(null)
                                        : copy(credentialOverride);
                    });
            return new Captured(selected[0], owned[0]);
        } catch (RuntimeException | Error failure) {
            if (owned[0] != null) Arrays.fill(owned[0], (byte) 0);
            throw failure;
        }
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static boolean sameVersionedConnection(
            AiProviderProfile requested, AiProviderProfile current) {
        return requested.id().equals(current.id())
                && requested.version() == current.version()
                && requested.providerKind() == current.providerKind()
                && normalize(requested.baseUrl()).equals(normalize(current.baseUrl()));
    }

    private static String normalize(String url) {
        return url.replaceAll("/+$", "");
    }

    private static void stale() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
    }
}
