package com.lumira.platform.mfa;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.ReauthenticationPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class MfaEnrollmentFailureTest {
    @Test
    void invalidSetupCodeConsumesAttemptButDoesNotSetSelfEnabled() {
        UUID userId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        String sessionHash = "a".repeat(64);
        var store = mock(MfaStore.class);
        var security = mock(AccountSecurityPort.class);
        var keys =
                new SecretKeys(
                        "primary=" + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                        "primary");
        byte[] secret = new byte[20];
        String envelope = keys.require().encrypt("mfa:" + userId + ":secret", secret);
        var authenticator =
                new MfaStore.Authenticator(
                        envelope,
                        "Authenticator",
                        false,
                        Instant.now().plusSeconds(60),
                        -1,
                        Instant.now(),
                        challengeId,
                        sessionHash,
                        0);
        when(store.lockUser(userId)).thenReturn(userId);
        when(store.findForUpdate(userId)).thenReturn(Optional.of(authenticator));
        when(store.incrementSetupAttempts(userId, challengeId)).thenReturn(1);
        var service =
                new MfaService(
                        store,
                        mock(AccountOwnerPort.class),
                        security,
                        mock(EffectiveAuthenticationPolicyPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(ReauthenticationPort.class),
                        keys,
                        mock(TransactionTemplate.class),
                        mock(AuditPort.class),
                        mock(com.lumira.platform.common.AdministrationPort.class),
                        mock(com.lumira.platform.common.MailSender.class),
                        mock(com.lumira.platform.identity.api.MfaRecoveryTicketPort.class),
                        mock(org.springframework.jdbc.core.simple.JdbcClient.class));

        var result = service.confirmEnrollment(userId, challengeId, sessionHash, "invalid", true);

        assertNull(result);
        verify(store).incrementSetupAttempts(userId, challengeId);
        verify(security, never()).setSecondFactorSources(userId, "mfa", true, false);
    }
}
