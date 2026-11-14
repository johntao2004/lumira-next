package com.lumira.platform.settings;

import com.lumira.platform.common.TransactionCachedValue;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 内核 WebAuthn 策略读取器独立于可选管理插件，并在事务内读取权威值，避免回滚污染其他请求缓存。 Kernel WebAuthn policy reader is independent
 * of its optional administration plugin, and reads authoritative values inside a transaction so a
 * rollback cannot pollute another request's cache.
 */
@Service
public class PasskeyPolicyReader implements PasskeyPolicyPort {
    public record View(long version, PasskeySettings data) {}

    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private final SettingStore store;
    private final ObjectMapper mapper;
    private final TransactionCachedValue<View> cache;

    public PasskeyPolicyReader(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        this.cache = new TransactionCachedValue<>(CACHE_TTL, this::load);
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

    public void invalidateAfterCommit() {
        cache.invalidateAfterCommit();
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
