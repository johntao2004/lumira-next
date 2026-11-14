package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.settings.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 每次从数据库权威读取安全策略并参与调用方现有事务，避免跨实例陈旧值及回滚后缓存污染；管理端写入仅在事务提交后使独立内核读取器失效。 Reads authoritative policy from
 * the database within the caller transaction to avoid stale nodes and rollback-polluted caches;
 * administration writes invalidate the independent kernel reader only after commit.
 */
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
