package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.RegistrationRolePort;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AccountProvisioningPort;
import com.lumira.platform.identity.api.AgreementSnapshotPort;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import com.lumira.platform.identity.api.ProfileRegistrationPort;
import com.lumira.platform.identity.api.RegistrationPolicyPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class PublicRegistrationCaptchaReadinessTest {
    @Test
    void emailRegistrationRemainsAvailableWhenCaptchaIsOffAndItsProviderIsNotReadyForRequiredUse() {
        UUID roleId = UUID.randomUUID();
        var registration = mock(RegistrationPolicyPort.class);
        when(registration.current())
                .thenReturn(
                        new RegistrationPolicyPort.Policy(
                                3, true, Set.of(RegistrationPolicyPort.Channel.EMAIL), roleId));
        var agreements = mock(AgreementSnapshotPort.class);
        when(agreements.current())
                .thenReturn(new AgreementSnapshotPort.Document(4, "user terms", "privacy terms"));
        var security = mock(SecurityPolicyPort.class);
        when(security.currentPolicy()).thenReturn(SecurityPolicyPort.Policy.defaults());
        var roles = mock(RegistrationRolePort.class);
        when(roles.candidate(roleId))
                .thenReturn(
                        new RegistrationRolePort.Candidate(
                                roleId, "member", "Member", 1, true, null));
        var maintenance = mock(MaintenancePolicyPort.class);
        when(maintenance.publicRegistrationAvailable()).thenReturn(true);
        var mail = mock(MailSender.class);
        when(mail.ready()).thenReturn(true);
        var captchaProvider =
                new CaptchaProofPort() {
                    @Override
                    public Requirement requirement(
                            Action action, UUID accountId, UUID defaultRoleId) {
                        return new Requirement(false, "captcha-off-v1", "NONE");
                    }

                    @Override
                    public void consume(
                            String proofId,
                            Action action,
                            String policyRevision,
                            RequestFacts requestFacts,
                            String expectedAttemptId) {}
                };
        @SuppressWarnings("unchecked")
        var captchaProviders = (ObjectProvider<CaptchaProofPort>) mock(ObjectProvider.class);
        when(captchaProviders.getIfAvailable()).thenReturn(captchaProvider);
        var captcha =
                new CaptchaProofAccess(
                        captchaProviders,
                        mock(ClientAddressResolver.class),
                        mock(PublicAuthCrypto.class));

        var service =
                new PublicRegistrationService(
                        mock(JdbcClient.class),
                        new ObjectMapper(),
                        registration,
                        mock(AuthenticationPolicyWriteLock.class),
                        roles,
                        agreements,
                        mock(AccountProvisioningPort.class),
                        mock(ProfileRegistrationPort.class),
                        provider(null),
                        provider(null),
                        maintenance,
                        security,
                        mock(PublicAuthChallengeStore.class),
                        mock(PublicAuthCrypto.class),
                        mock(PublicAuthRateLimiter.class),
                        captcha,
                        mock(ClientAddressResolver.class),
                        mail,
                        mock(SmsSender.class),
                        provider(null),
                        mock(AuditPort.class),
                        mock(PlatformTransactionManager.class));

        var config = service.config();

        assertTrue(config.available());
        assertEquals(List.of("EMAIL"), config.channels());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        var provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
