package com.lumira.plugins.deliveryconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformPageId;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global operational metadata only. Encrypted SMS payloads and lease tokens never leave the worker
 * boundary.
 */
@Service
public class SmsDeliveryQuery {
    public record Entry(
            UUID id,
            String state,
            int attempts,
            String lastResult,
            OffsetDateTime availableAt,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private static final Set<String> STATES =
            Set.of("PENDING", "LEASED", "SENDING", "ACCEPTED", "FAILED", "UNCERTAIN", "EXPIRED");
    private static final String FILTER =
            " WHERE (:state='' OR state=:state) AND (:search='' OR strpos(id::text,lower(:search))>0)";
    private final JdbcClient jdbc;
    private final AdministrationPort guard;

    public SmsDeliveryQuery(JdbcClient jdbc, AdministrationPort guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Entry> list(String actor, PageRequest page, String state) {
        guard.requirePageGlobal(actor, PlatformPageId.SMS_DELIVERIES);
        if (state == null || (!state.isEmpty() && !STATES.contains(state))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSmsState");
        }
        var queryParameters = DeliveryPageQuerySupport.parameters(page, state);
        var rows =
                jdbc.sql(
                                "SELECT id,state,attempts,last_result,available_at,expires_at,created_at,updated_at "
                                        + "FROM platform_sms_delivery"
                                        + FILTER
                                        + queryParameters.filterSql()
                                        + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
                        .params(queryParameters.rowsParameters())
                        .query(Entry.class)
                        .list();
        var count =
                jdbc.sql(
                                "SELECT count(*) FROM platform_sms_delivery"
                                        + FILTER
                                        + queryParameters.filterSql())
                        .params(queryParameters.countParameters())
                        .query(Long.class)
                        .single();
        return new PageResult<>(rows, count, page.page(), page.pageSize());
    }
}
