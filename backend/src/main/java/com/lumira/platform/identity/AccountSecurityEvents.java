package com.lumira.platform.identity;

import com.lumira.platform.common.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountSecurityEvents {
    public record Event(
            UUID id,
            String action,
            OffsetDateTime createdAt,
            String clientAddress,
            String userAgent) {}

    private record Owner(UUID id, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    public AccountSecurityEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String FILTER =
            """
  WHERE ((target_id=:owner AND action IN ('account.loginSucceeded','account.loginFailed','account.loginRateLimited','account.loginRejected','account.passwordChanged','account.passwordRecoveryRequested','account.passwordRecovered',
   'account.passwordRecoveryFailed','account.emailVerificationRequested','account.emailVerified','account.emailVerificationFailed',
   'mfa.enabled','mfa.disabled','mfa.renamed','mfa.recoveryRegenerated','mfa.adminReset'))
   OR (actor=:actor AND action='session.revoke' AND created_at>=:created))
  """;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Event> list(String actor, PageRequest page) {
        var owner =
                jdbc.sql(
                                "SELECT id,created_at FROM platform_user WHERE username=:actor AND enabled=true")
                        .param("actor", actor)
                        .query(Owner.class)
                        .optional()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var filter =
                PageFilterSql.build(
                        page,
                        java.util.Map.of(
                                "action",
                                "action",
                                "createdAt",
                                "created_at",
                                "clientAddress",
                                "client_address",
                                "userAgent",
                                "user_agent"));
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("owner", owner.id());
        parameters.put("actor", actor);
        parameters.put("created", owner.createdAt());
        parameters.putAll(filter.parameters());
        var count =
                jdbc.sql("SELECT count(*) FROM platform_audit " + FILTER + filter.sql())
                        .params(parameters)
                        .query(Long.class)
                        .single();
        var rowsParameters = new java.util.HashMap<>(parameters);
        rowsParameters.put("limit", page.pageSize());
        rowsParameters.put("offset", page.offset());
        var rows =
                jdbc.sql(
                                "SELECT id,action,created_at,client_address,user_agent FROM platform_audit "
                                        + FILTER
                                        + filter.sql()
                                        + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
                        .params(rowsParameters)
                        .query(Event.class)
                        .list();
        return new PageResult<>(rows, count, page.page(), page.pageSize());
    }
}
