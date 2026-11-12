package com.lumira.platform.settings;

import com.lumira.platform.common.TransactionCachedValue;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Kernel policy reader remains active when its optional administration plugin is absent. */
@Service
public class SecurityPolicyReader implements SecurityPolicyPort {
    public record View(long version, SecuritySettings data) {}

    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private final SettingStore store;
    private final ObjectMapper mapper;
    private final TransactionCachedValue<View> cache;

    public SecurityPolicyReader(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        this.cache = new TransactionCachedValue<>(CACHE_TTL, this::load);
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

    public void invalidateAfterCommit() {
        cache.invalidateAfterCommit();
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
