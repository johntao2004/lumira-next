package com.lumira.platform.settings;

import com.lumira.platform.identity.api.PasskeyPolicyPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 每次从数据库权威读取安全策略并参与调用方现有事务，避免跨实例陈旧值及回滚后缓存污染。 Reads authoritative policy from the database to avoid
 * stale nodes and rollback-polluted caches.
 */
@Service
public class PasskeySettingsService implements PasskeyPolicyPort {
    public record View(long version, PasskeySettings data) {}

    private final SettingStore store;
    private final ObjectMapper mapper;

    public PasskeySettingsService(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public View read() {
        return load();
    }

    public PasskeySettings current() {
        return load().data();
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
