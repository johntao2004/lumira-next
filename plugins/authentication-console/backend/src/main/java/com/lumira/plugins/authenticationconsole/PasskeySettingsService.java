package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.settings.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 每次从数据库权威读取通行密钥策略并参与调用方现有事务，避免跨实例陈旧值及回滚后缓存污染；管理端写入仅在事务提交后使独立内核读取器失效。 Reads authoritative passkey
 * policy from the database within the caller transaction to avoid stale nodes and rollback-polluted
 * caches; administration writes invalidate the independent kernel reader only after commit.
 */
@Service
public class PasskeySettingsService {
    public record View(long version, PasskeySettings data) {}

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final PasskeyPolicyReader policies;

    public PasskeySettingsService(
            SettingStore store, ObjectMapper mapper, PasskeyPolicyReader policies) {
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
        policies.invalidateAfterCommit();
        return result;
    }
}
