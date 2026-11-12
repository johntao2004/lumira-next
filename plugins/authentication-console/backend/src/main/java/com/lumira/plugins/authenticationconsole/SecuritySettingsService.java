package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.settings.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Administration writes invalidate the independent kernel reader only after commit. */
@Service
public class SecuritySettingsService {
    public record View(long version, SecuritySettings data) {}

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final SecurityPolicyReader policies;

    public SecuritySettingsService(
            SettingStore store, ObjectMapper mapper, SecurityPolicyReader policies) {
        this.store = store;
        this.mapper = mapper;
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public View read() {
        var current = policies.read();
        return new View(current.version(), current.data());
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
        policies.invalidateAfterCommit();
        return result;
    }
}
