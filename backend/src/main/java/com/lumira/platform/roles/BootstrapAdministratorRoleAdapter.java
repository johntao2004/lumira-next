package com.lumira.platform.roles;

import com.lumira.platform.common.BootstrapRolePort;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Assigns the seeded administrator role while creating the first platform account. */
@Service
public class BootstrapAdministratorRoleAdapter implements BootstrapRolePort {
    public static final UUID ADMINISTRATOR_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000052");

    private final JdbcClient jdbc;

    public BootstrapAdministratorRoleAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The role must be the fixed, enabled administrator seed. This method only writes membership;
     * it never grants user authorities. IdentityConfiguration calls it only in the empty-database
     * branch, so later startups cannot restore a removed membership.
     */
    @Override
    public void assignAdministratorRole(UUID userId) {
        boolean roleAvailable =
                jdbc.sql(
                                "SELECT id FROM platform_role WHERE id=:roleId AND code='admin' AND enabled AND data_scope='ALL' FOR SHARE")
                        .param("roleId", ADMINISTRATOR_ROLE_ID)
                        .query(UUID.class)
                        .optional()
                        .isPresent();
        if (!roleAvailable)
            throw new IllegalStateException(
                    "The seeded administrator role is missing, disabled, or not global-scope");

        jdbc.sql(
                        "INSERT INTO platform_user_role(user_id,role_id) VALUES(:userId,:roleId) ON CONFLICT(user_id,role_id) DO NOTHING")
                .param("userId", userId)
                .param("roleId", ADMINISTRATOR_ROLE_ID)
                .update();
    }
}
