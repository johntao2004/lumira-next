package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted login policy remains available without its optional administration workflow. */
@Service
public class GlobalAuthenticationPolicyReader implements GlobalAuthenticationPolicyPort {
    private final JdbcClient jdbc;

    public GlobalAuthenticationPolicyReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean supportsUnifiedDatabaseProjection() {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Policy current() {
        return jdbc.sql(
                        """
                        SELECT version,revision,enabled_methods,mfa_required
                          FROM platform_global_authentication_policy WHERE id=1
                        """)
                .query(
                        (rs, row) -> {
                            var methods = EnumSet.noneOf(PrimaryMethod.class);
                            java.sql.Array array = rs.getArray("enabled_methods");
                            if (array != null) {
                                try {
                                    Object raw = array.getArray();
                                    if (raw instanceof Object[] items) {
                                        for (Object item : items) {
                                            if (item != null) {
                                                methods.add(PrimaryMethod.valueOf(item.toString()));
                                            }
                                        }
                                    }
                                } finally {
                                    array.free();
                                }
                            }
                            return new Policy(
                                    rs.getLong("version"),
                                    rs.getObject("revision", UUID.class),
                                    methods,
                                    rs.getBoolean("mfa_required"));
                        })
                .single();
    }
}
