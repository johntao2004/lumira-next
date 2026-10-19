package com.lumira.platform.settings;

import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 每次从数据库权威读取安全策略并参与调用方现有事务，避免跨实例陈旧值及回滚后缓存污染。 Reads authoritative policy from the database to avoid
 * stale nodes and rollback-polluted caches.
 */
@Service
public class SecuritySettingsService implements SecurityPolicyPort {
    public record View(long version, SecuritySettings data) {}

    private final SettingStore store;
    private final ObjectMapper mapper;

    public SecuritySettingsService(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public View read() {
        return load();
    }

    public SecuritySettings current() {
        return load().data();
    }

    @Override
    public SecurityPolicyPort.Policy currentPolicy() {
        return current().policy();
    }

    @Transactional
    public View save(
            SecuritySettings data, String actor, long expectedVersion, String changeReason) {
        if (data == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidSecurity");
        data.validate();
        SettingStore.Snapshot saved =
                store.save(
                        "security",
                        mapper.writeValueAsString(data),
                        actor,
                        expectedVersion,
                        changeReason);
        View result = new View(saved.version(), data);
        return result;
    }

    private View load() {
        return store.find("security")
                .map(
                        snapshot ->
                                new View(
                                        snapshot.version(),
                                        mapper.readValue(
                                                snapshot.payload(), SecuritySettings.class)))
                .orElseGet(() -> new View(0, SecuritySettings.defaults()));
    }
}
