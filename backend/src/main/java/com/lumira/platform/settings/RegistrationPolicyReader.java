package com.lumira.platform.settings;

import com.lumira.platform.identity.api.RegistrationPolicyPort;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current and row-locked policy reads do not depend on the optional editor. */
@Service
public class RegistrationPolicyReader implements RegistrationPolicyPort {
    private final JdbcClient jdbc;

    public RegistrationPolicyReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Policy current() {
        return policy(false);
    }

    @Override
    @Transactional
    public Policy lockCurrent() {
        return policy(true);
    }

    private Policy policy(boolean lock) {
        return jdbc.sql(
                        "SELECT version,enabled,channels,default_role_id FROM platform_registration_policy WHERE id=1"
                                + (lock ? " FOR SHARE" : ""))
                .query(
                        (rs, row) -> {
                            var channels = EnumSet.noneOf(Channel.class);
                            for (String channel : (String[]) rs.getArray("channels").getArray())
                                channels.add(Channel.valueOf(channel));
                            return new Policy(
                                    rs.getLong("version"),
                                    rs.getBoolean("enabled"),
                                    channels,
                                    rs.getObject("default_role_id", UUID.class));
                        })
                .single();
    }
}
