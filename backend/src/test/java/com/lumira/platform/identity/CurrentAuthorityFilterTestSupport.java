package com.lumira.platform.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort.EffectivePolicy;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Supplies current persisted grants and policy while leaving the security filter enabled. */
final class CurrentAuthorityFilterTestSupport {
    private static final String CONTEXT_QUERY =
            """
            SELECT u.id,a.authority FROM platform_user u
              LEFT JOIN platform_effective_authority a ON a.user_id=u.id
             WHERE u.username=:name AND u.enabled=true AND u.deleted_at IS NULL
            """;
    private static final UUID USER_ID = UUID.fromString("5f7b427e-c0e8-4d75-9cc3-e078b3aa6400");
    private static final UUID POLICY_REVISION =
            UUID.fromString("8395eb54-cff1-4f95-8b51-8d82148c558b");
    private static final UUID PRIMARY_CREDENTIAL_REVISION =
            UUID.fromString("7b57f1bc-56af-4a04-a60e-1b6d388be379");
    private static final UUID FACTOR_CREDENTIAL_REVISION =
            UUID.fromString("7ef39378-8ccc-4bd3-9441-56a61a72efb6");
    static final String CONTEXT_REVISION = "2f5908fc-6f2b-41a2-b53b-e1f38d06717c";

    private CurrentAuthorityFilterTestSupport() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void install(
            JdbcClient jdbc,
            AuthenticationPolicyService policies,
            TransactionTemplate transactions,
            RolePreviewContextService rolePreviewContexts,
            Function<String, List<String>> grantsForUsername)
            throws Exception {
        doAnswer(
                        invocation -> {
                            TransactionCallback callback = invocation.getArgument(0);
                            return callback.doInTransaction(mock(TransactionStatus.class));
                        })
                .when(transactions)
                .execute(any(TransactionCallback.class));

        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(CONTEXT_QUERY)).thenReturn(statement);
        var username = new java.util.concurrent.atomic.AtomicReference<String>();
        when(statement.param(eq("name"), any()))
                .thenAnswer(
                        invocation -> {
                            username.set(invocation.getArgument(1));
                            return statement;
                        });
        doAnswer(
                        invocation -> {
                            RowCallbackHandler handler = invocation.getArgument(0);
                            for (String authority : grantsForUsername.apply(username.get())) {
                                ResultSet row = mock(ResultSet.class);
                                when(row.getObject("id", UUID.class)).thenReturn(USER_ID);
                                when(row.getString("authority")).thenReturn(authority);
                                handler.processRow(row);
                            }
                            return null;
                        })
                .when(statement)
                .query(any(RowCallbackHandler.class));

        when(policies.resolve(USER_ID))
                .thenReturn(
                        new EffectivePolicy(
                                POLICY_REVISION,
                                Set.of(PrimaryMethod.PASSWORD),
                                Set.of(PrimaryMethod.PASSWORD),
                                false,
                                List.of(),
                                false,
                                PRIMARY_CREDENTIAL_REVISION,
                                FACTOR_CREDENTIAL_REVISION));

        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            HttpServletRequest request = invocation.getArgument(0);
                            String principal = invocation.getArgument(1);
                            UUID actorId = invocation.getArgument(2);
                            @SuppressWarnings("unchecked")
                            Set<String> authorities = invocation.getArgument(3);
                            var values = Set.copyOf(authorities);
                            var globalValues = globalAuthorities(values);
                            return new RequestAuthorizationContext(
                                    principal,
                                    actorId,
                                    request.getSession(false).getId(),
                                    values,
                                    globalValues,
                                    java.util.Map.of(),
                                    values,
                                    globalValues,
                                    java.util.Map.of(),
                                    CONTEXT_REVISION,
                                    request.getHeader(RolePreviewContextService.REVISION_HEADER),
                                    true,
                                    null);
                        })
                .when(rolePreviewContexts)
                .resolve(any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            HttpServletRequest request = invocation.getArgument(0);
                            RequestAuthorizationContext context = invocation.getArgument(1);
                            if (!java.util.Objects.equals(
                                    CONTEXT_REVISION,
                                    request.getHeader(RolePreviewContextService.REVISION_HEADER))) {
                                throw new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.CONFLICT,
                                        "authorizationContextChanged");
                            }
                            return null;
                        })
                .when(rolePreviewContexts)
                .enforceRequestBoundary(any(), any());
    }

    static MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute(
                AuthenticationSession.PRIMARY_CREDENTIAL_REVISION,
                PRIMARY_CREDENTIAL_REVISION.toString());
        return session;
    }

    private static Set<String> globalAuthorities(Set<String> authorities) {
        var global = new java.util.HashSet<String>();
        for (PlatformPageId page : PlatformPageId.values())
            if (page.scope() == PlatformOperation.Scope.GLOBAL
                    && authorities.contains(page.accessPermission()))
                global.add(page.accessPermission());
        for (PlatformOperation operation : PlatformOperation.values())
            if (operation.scope() == PlatformOperation.Scope.GLOBAL
                    && authorities.contains(operation.permission()))
                global.add(operation.permission());
        return Set.copyOf(global);
    }
}
