package com.lumira.platform.settings;

import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Security policy with a short, bounded cache for login hot paths. */
@Service
public class SecuritySettingsService implements SecurityPolicyPort {
    public record View(long version, SecuritySettings data) {}
    private record Cached(View view, Instant expiresAt) {}

    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private final SettingStore store;
    private final ObjectMapper mapper;
    private volatile Cached cached;

    public SecuritySettingsService(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public View read() {
        View view = load();
        cached = new Cached(view, Instant.now().plus(CACHE_TTL));
        return view;
    }

    /** Reads the policy without making every login or code request hit SQL. */
    public SecuritySettings current() {
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
    public SecurityPolicyPort.Policy currentPolicy() {
        SecuritySettings value = current();
        return new SecurityPolicyPort.Policy(
                value.passwordMinLength(), value.passwordRequireUppercase(), value.passwordRequireLowercase(),
                value.passwordRequireSpecialCharacter(), value.passwordAllowConsecutiveCharacters(),
                value.loginAccountPerMinute(), value.loginSourcePerMinute(),
                value.recoveryAccountPerMinute(), value.recoverySourcePerMinute(),
                value.verificationCodeExpireSeconds(), value.verificationCodeCooldownSeconds(),
                value.verificationRequestsPerMinute(), value.verificationDailyLimit(),
                value.verificationFailureLimit());
    }

    @Transactional
    public View save(SecuritySettings data, String actor, long expectedVersion, String changeReason) {
        return save(data, actor, expectedVersion, changeReason, null);
    }

    public View save(SecuritySettings data, String actor, long expectedVersion, String changeReason, Long sourceVersion) {
        if (data == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "invalidSecurity");
        data.validate();
        SettingStore.Snapshot saved = store.save("security", mapper.writeValueAsString(data), actor, expectedVersion, changeReason, sourceVersion);
        View result = new View(saved.version(), data);
        cached = new Cached(result, Instant.now().plus(CACHE_TTL));
        return result;
    }

    private View load() {
        return store.find("security")
                .map(snapshot -> new View(snapshot.version(), mapper.readValue(snapshot.payload(), SecuritySettings.class)))
                .orElseGet(() -> new View(0, SecuritySettings.defaults()));
    }
}
