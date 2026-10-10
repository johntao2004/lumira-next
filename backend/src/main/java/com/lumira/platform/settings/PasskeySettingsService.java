package com.lumira.platform.settings;

import com.lumira.platform.common.ExpiringValue;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** WebAuthn policy with a short cache for login and registration options. */
@Service
public class PasskeySettingsService implements PasskeyPolicyPort {
    public record View(long version, PasskeySettings data) {}

    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private final SettingStore store;
    private final ObjectMapper mapper;
    private final ExpiringValue<View> cache;

    public PasskeySettingsService(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        this.cache = new ExpiringValue<>(CACHE_TTL, this::load);
    }

    @Transactional(readOnly = true)
    public View read() {
        return cache.refresh();
    }

    public PasskeySettings current() {
        return cache.get().data();
    }

    @Override
    public PasskeyPolicyPort.Policy currentPolicy() {
        PasskeySettings value = current();
        return new PasskeyPolicyPort.Policy(
                value.enabled(),
                value.passwordlessEnabled(),
                value.selfBindingEnabled(),
                value.rpId(),
                value.rpName(),
                value.allowedOrigins(),
                value.challengeTtlSeconds());
    }

    @Transactional
    public View save(
            PasskeySettings data, String actor, long expectedVersion, String changeReason) {
        return save(data, actor, expectedVersion, changeReason, null);
    }

    public View save(
            PasskeySettings data,
            String actor,
            long expectedVersion,
            String changeReason,
            Long sourceVersion) {
        if (data == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidPasskeySettings");
        data = data.normalized();
        data.validate();
        SettingStore.Snapshot saved =
                store.save(
                        "passkey",
                        mapper.writeValueAsString(data),
                        actor,
                        expectedVersion,
                        changeReason,
                        sourceVersion);
        View result = new View(saved.version(), data);
        cache.put(result);
        return result;
    }

    private View load() {
        return store.find("passkey")
                .map(
                        snapshot ->
                                new View(
                                        snapshot.version(),
                                        mapper.readValue(snapshot.payload(), PasskeySettings.class)
                                                .normalized()))
                .orElseGet(() -> new View(0, PasskeySettings.defaults()));
    }
}
