package com.lumira.platform.settings;

import com.lumira.platform.common.ExpiringValue;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Security policy with a short, bounded cache for login hot paths. */
@Service
public class SecuritySettingsService implements SecurityPolicyPort {
    public record View(long version, SecuritySettings data) {}

    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private final SettingStore store;
    private final ObjectMapper mapper;
    private final ExpiringValue<View> cache;

    public SecuritySettingsService(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        this.cache = new ExpiringValue<>(CACHE_TTL, this::load);
    }

    @Transactional(readOnly = true)
    public View read() {
        return cache.refresh();
    }

    public SecuritySettings current() {
        return cache.get().data();
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
        cache.put(result);
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
