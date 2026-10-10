package com.lumira.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.ReauthenticationPort;
import com.lumira.platform.identity.api.WechatConfigurationPort;
import com.lumira.platform.identity.api.WechatRegistrationBindingPort;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class WechatRegistrationBindingPortTest {
    @Test
    void publicPortAcceptsOnlyJdkTypesAndCarriesTheSessionDigest() throws Exception {
        Method method =
                WechatRegistrationBindingPort.class.getMethod(
                        "consumeForRegistration",
                        String.class,
                        String.class,
                        UUID.class,
                        String.class);

        assertThat(method.getParameterTypes())
                .containsExactly(String.class, String.class, UUID.class, String.class);
        assertThat(method.getReturnType())
                .isEqualTo(WechatRegistrationBindingPort.BindingResult.class);
    }

    @Test
    void malformedSessionDigestFailsClosedBeforeTouchingTheTransaction() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        var service =
                new WechatIdentityService(
                        mock(JdbcClient.class),
                        tx,
                        new JsonMapper(),
                        mock(PublicAuthCrypto.class),
                        mock(PublicAuthChallengeStore.class),
                        mock(CaptchaProofAccess.class),
                        mock(WechatConfigurationPort.class),
                        mock(WechatProviderClient.class),
                        mock(GlobalAuthenticationPolicyPort.class),
                        mock(AccountOwnerPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(EffectiveAuthenticationPolicyPort.class),
                        mock(PrimaryAuthenticationPort.class),
                        mock(UserDetailsService.class),
                        mock(ReauthenticationPort.class),
                        mock(AccountSecurityPort.class),
                        mock(WechatRateLimiter.class));

        var result =
                service.consumeForRegistration(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID(),
                        "A".repeat(64));

        assertThat(result.status()).isEqualTo(WechatRegistrationBindingPort.Status.FAILED);
        assertThat(result.reason()).isEqualTo("wechatRegistrationFailed");
        verifyNoInteractions(tx);
    }
}
