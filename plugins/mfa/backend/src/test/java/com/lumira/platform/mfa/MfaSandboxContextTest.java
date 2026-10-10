package com.lumira.platform.mfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.MfaRecoveryTicketPort;
import com.lumira.platform.identity.api.ReauthenticationPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import com.lumira.plugin.sdk.PluginResources;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class MfaSandboxContextTest {
    @Test
    void childPluginContextUsesHostSecretKeysAndDefersMissingKeyFailureToSecretUse() {
        var host = new AnnotationConfigApplicationContext();
        var owners = mock(AccountOwnerPort.class);
        var security = mock(AccountSecurityPort.class);
        var audit = mock(AuditPort.class);
        var administration = mock(AdministrationPort.class);
        var mail = mock(MailSender.class);
        var recoveryTickets = mock(MfaRecoveryTicketPort.class);
        var policyLock = mock(AuthenticationPolicyWriteLock.class);
        var reauthentication = mock(ReauthenticationPort.class);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        var tx = mock(TransactionTemplate.class);
        var store = mock(MfaStore.class);
        UUID userId = UUID.randomUUID();

        host.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
        host.registerBean(AccountOwnerPort.class, () -> owners);
        host.registerBean(AccountSecurityPort.class, () -> security);
        host.registerBean(AuditPort.class, () -> audit);
        host.registerBean(AdministrationPort.class, () -> administration);
        host.registerBean(MailSender.class, () -> mail);
        host.registerBean(MfaRecoveryTicketPort.class, () -> recoveryTickets);
        host.registerBean(AuthenticationPolicyWriteLock.class, () -> policyLock);
        host.registerBean(ReauthenticationPort.class, () -> reauthentication);
        host.registerBean(EffectiveAuthenticationPolicyPort.class, () -> policies);
        host.registerBean(TransactionTemplate.class, () -> tx);
        host.registerBean(SecretKeys.class, () -> new SecretKeys("", "primary"));
        host.refresh();

        doAnswer(
                        invocation -> {
                            TransactionCallback<?> callback = invocation.getArgument(0);
                            return callback.doInTransaction(new SimpleTransactionStatus());
                        })
                .when(tx)
                .execute(any(TransactionCallback.class));
        try (host) {
            var plugin = new AnnotationConfigApplicationContext();
            plugin.setParent(host);
            plugin.setClassLoader(LumiraPlugin.class.getClassLoader());
            plugin.registerBean(PluginResources.class, () -> mock(PluginResources.class));
            plugin.registerBean(
                    "testMfaStore",
                    MfaStore.class,
                    () -> store,
                    definition -> definition.setPrimary(true));
            plugin.register(LumiraPlugin.MfaConfiguration.class);

            try (plugin) {
                plugin.refresh();
                var service = plugin.getBean(MfaService.class);
                assertNotNull(plugin.getBean(MfaController.class));
                assertSame(service, plugin.getBean(SecondFactorProvider.class));
                org.junit.jupiter.api.Assertions.assertFalse(service.available());

                var error =
                        assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        service.setup(
                                                "alice",
                                                UUID.randomUUID(),
                                                "Authenticator",
                                                "session-id"));
                assertEquals(503, error.getStatusCode().value());
                assertEquals("secondFactorUnavailable", error.getReason());
            }
        }
    }
}
