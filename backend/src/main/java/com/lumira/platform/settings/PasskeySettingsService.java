package com.lumira.platform.settings;

import com.lumira.platform.identity.api.PasskeyPolicyPort;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** WebAuthn policy with a short cache for login and registration options. */
@Service
public class PasskeySettingsService implements PasskeyPolicyPort {
    public record View(long version, PasskeySettings data) {}
    private record Cached(View view, Instant expiresAt) {}

    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private final SettingStore store;
    private final ObjectMapper mapper;
    private volatile Cached cached;

    public PasskeySettingsService(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public View read() {
        View view = load();
        cached = new Cached(view, Instant.now().plus(CACHE_TTL));
        return view;
    }

    public PasskeySettings current() {
        Cached value = cached;
        Instant now = Instant.now();
        if (value != null && now.isBefore(value.expiresAt())) return value.view().data();
        synchronized (this) {
            value = cached;
            now = Instant.now();
            if (value == null || !now.isBefore(value.expiresAt())) {
                View view = load();
                cached = value = new Cached(view, now.plus(CACHE_TTL));
            }
            return value.view().data();
        }
    }

    @Override
    public PasskeyPolicyPort.Policy currentPolicy() {
        PasskeySettings value = current();
        return new PasskeyPolicyPort.Policy(value.enabled(), value.passwordlessEnabled(), value.selfBindingEnabled(),
                value.rpId(), value.rpName(), value.allowedOrigins(), value.challengeTtlSeconds());
    }

    @Transactional
    public View save(PasskeySettings data, String actor, long expectedVersion, String changeReason) {
        return save(data, actor, expectedVersion, changeReason, null);
    }

    public View save(PasskeySettings data, String actor, long expectedVersion, String changeReason, Long sourceVersion) {
        if (data == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "invalidPasskeySettings");
        data = data.normalized();
        data.validate();
        SettingStore.Snapshot saved = store.save("passkey", mapper.writeValueAsString(data), actor, expectedVersion, changeReason, sourceVersion);
        View result = new View(saved.version(), data);
        cached = new Cached(result, Instant.now().plus(CACHE_TTL));
        return result;
    }

    private View load() {
        return store.find("passkey")
                .map(snapshot -> new View(snapshot.version(), mapper.readValue(snapshot.payload(), PasskeySettings.class).normalized()))
                .orElseGet(() -> new View(0, PasskeySettings.defaults()));
    }
}
