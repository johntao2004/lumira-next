package com.lumira.platform.notifications;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Shared audience check for feedback notifications using the same global grants as management. */
@Component
final class FeedbackNotificationAudience {
    private static final String ACCESS = "platform:feedback:access";
    private static final String READ = "platform:feedback:read";

    private final JdbcClient jdbc;

    FeedbackNotificationAudience(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean isGlobalManager(UUID userId) {
        if (userId == null) return false;
        return jdbc.sql(
                        """
                        SELECT EXISTS (
                            SELECT 1
                              FROM platform_global_authority access_grant
                             WHERE access_grant.user_id=:userId
                               AND access_grant.authority=:access
                               AND EXISTS (
                                   SELECT 1
                                     FROM platform_global_authority read_grant
                                    WHERE read_grant.user_id=access_grant.user_id
                                      AND read_grant.authority=:read
                               )
                        )
                        """)
                .param("userId", userId)
                .param("access", ACCESS)
                .param("read", READ)
                .query(Boolean.class)
                .single();
    }
}
