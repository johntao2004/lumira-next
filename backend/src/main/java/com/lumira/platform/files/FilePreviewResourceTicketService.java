package com.lumira.platform.files;

import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Issues one-use, session-bound tickets for the one native resource needed in role preview. */
@Service
public class FilePreviewResourceTicketService {
    private static final Pattern FILE_PREVIEW_PATH =
            Pattern.compile(
                    "^/api/account/files/([0-9a-fA-F-]{36})/preview(?:\\?page=([1-9][0-9]{0,2}))?$");
    private static final int TICKET_SECONDS = 60;

    public record Ticket(String url, OffsetDateTime expiresAt) {}

    private record FileClaim(UUID fileId, int page) {}

    private final JdbcClient jdbc;
    private final FileService files;
    private final AuthorizationContextBoundaryPort boundary;
    private final TransactionTemplate transaction;

    public FilePreviewResourceTicketService(
            JdbcClient jdbc,
            FileService files,
            AuthorizationContextBoundaryPort boundary,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.files = files;
        this.boundary = boundary;
        this.transaction = new TransactionTemplate(manager);
    }

    public Ticket issue(String path, String requestedRevision) {
        FileClaim claim = parse(path);
        return transaction.execute(
                status -> {
                    boundary.lockAndValidateCurrentRequest();
                    RequestAuthorizationContext context = authorization();
                    requireActiveRevision(context, requestedRevision);
                    var file = files.find(context.principalName(), claim.fileId());
                    if (!"READY".equals(file.state()))
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");

                    UUID ticketId = UUID.randomUUID();
                    OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(TICKET_SECONDS);
                    int inserted =
                            jdbc.sql(
                                            """
                            INSERT INTO platform_role_preview_resource_ticket(
                                ticket_id,session_primary_id,actor_id,context_revision,
                                resource_type,resource_id,page_number,expires_at)
                            VALUES(:ticket,:session,:actor,:revision,'FILE_PREVIEW',:resource,:page,:expires)
                            """)
                                    .param("ticket", ticketId)
                                    .param("session", context.sessionPrimaryId())
                                    .param("actor", context.actorId())
                                    .param("revision", UUID.fromString(context.revision()))
                                    .param("resource", claim.fileId())
                                    .param("page", claim.page())
                                    .param("expires", expiresAt)
                                    .update();
                    if (inserted != 1) throw new IllegalStateException("resourceTicketUnavailable");
                    return new Ticket(
                            "/account/role-preview/resource-tickets/" + ticketId, expiresAt);
                });
    }

    public FilePreview consumeAndRead(UUID ticketId) throws java.io.IOException {
        TicketClaim claim =
                transaction.execute(
                        status -> {
                            boundary.lockAndValidateCurrentRequest();
                            RequestAuthorizationContext context = authorization();
                            if (context == null || !context.previewActive())
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, "rolePreviewReadOnly");
                            UUID revision = UUID.fromString(context.revision());
                            TicketClaim locked =
                                    jdbc.sql(
                                                    """
                            SELECT resource_id,page_number
                              FROM platform_role_preview_resource_ticket
                             WHERE ticket_id=:ticket
                               AND session_primary_id=:session
                               AND actor_id=:actor
                               AND context_revision=:revision
                               AND resource_type='FILE_PREVIEW'
                               AND consumed_at IS NULL
                               AND expires_at>CURRENT_TIMESTAMP
                             FOR UPDATE
                            """)
                                            .param("ticket", ticketId)
                                            .param("session", context.sessionPrimaryId())
                                            .param("actor", context.actorId())
                                            .param("revision", revision)
                                            .query(
                                                    (rs, row) ->
                                                            new TicketClaim(
                                                                    rs.getObject(
                                                                            "resource_id",
                                                                            UUID.class),
                                                                    rs.getInt("page_number")))
                                            .optional()
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "resourceTicketUnavailable"));
                            int consumed =
                                    jdbc.sql(
                                                    """
                            UPDATE platform_role_preview_resource_ticket
                               SET consumed_at=CURRENT_TIMESTAMP
                             WHERE ticket_id=:ticket AND consumed_at IS NULL
                            """)
                                            .param("ticket", ticketId)
                                            .update();
                            if (consumed != 1)
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "resourceTicketUnavailable");
                            return locked;
                        });
        return files.preview(authorization().principalName(), claim.fileId(), claim.page());
    }

    private static RequestAuthorizationContext authorization() {
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null || snapshot.authorization() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticationRequired");
        return snapshot.authorization();
    }

    private static void requireActiveRevision(
            RequestAuthorizationContext context, String requestedRevision) {
        if (context == null || !context.previewActive())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewReadOnly");
        if (requestedRevision == null || !requestedRevision.equals(context.revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "authorizationContextChanged");
    }

    private static FileClaim parse(String path) {
        if (path == null || path.length() > 160) throw invalidPath();
        Matcher matcher = FILE_PREVIEW_PATH.matcher(path);
        if (!matcher.matches()) throw invalidPath();
        UUID fileId;
        int page;
        try {
            fileId = UUID.fromString(matcher.group(1));
            page = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        } catch (IllegalArgumentException invalid) {
            throw invalidPath();
        }
        if (page < 1 || page > 500) throw invalidPath();
        return new FileClaim(fileId, page);
    }

    private static ResponseStatusException invalidPath() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidResourceTicketPath");
    }

    private record TicketClaim(UUID fileId, int page) {}
}
