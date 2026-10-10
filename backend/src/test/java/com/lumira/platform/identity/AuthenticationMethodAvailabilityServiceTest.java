package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.WechatAvailabilityPort;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AuthenticationMethodAvailabilityServiceTest {
    @Test
    void exposesOnlyMethodsWhoseRealProvidersAreReady() {
        var mail = mock(MailSender.class);
        when(mail.ready()).thenReturn(true);
        var sms = mock(SmsSender.class);
        when(sms.ready()).thenReturn(true);
        var passkeys = mock(PasskeyPolicyPort.class);
        when(passkeys.currentPolicy())
                .thenReturn(
                        new PasskeyPolicyPort.Policy(
                                true,
                                true,
                                true,
                                "example.test",
                                "Lumira",
                                java.util.List.of(),
                                300));
        var wechat = mock(WechatAvailabilityPort.class);
        when(wechat.ready()).thenReturn(true);
        when(wechat.currentAppId()).thenReturn("app-current");
        var wechatProviders = mock(ObjectProvider.class);
        when(wechatProviders.getIfAvailable()).thenReturn(wechat);

        var availability =
                new AuthenticationMethodAvailabilityService(mail, sms, passkeys, wechatProviders);

        assertEquals(
                Set.of(
                        PrimaryMethod.PASSWORD,
                        PrimaryMethod.PASSKEY,
                        PrimaryMethod.EMAIL_OTP,
                        PrimaryMethod.SMS_OTP,
                        PrimaryMethod.WECHAT),
                availability.availableMethods());
    }

    @Test
    void missingOrUnconfiguredProvidersCannotMakeTheirMethodsEnableable() {
        var mail = mock(MailSender.class);
        when(mail.ready()).thenReturn(false);
        var sms = mock(SmsSender.class);
        when(sms.ready()).thenReturn(false);
        var passkeys = mock(PasskeyPolicyPort.class);
        when(passkeys.currentPolicy())
                .thenReturn(
                        new PasskeyPolicyPort.Policy(
                                false, false, false, "", "Lumira", java.util.List.of(), 300));
        var wechatProviders = mock(ObjectProvider.class);
        when(wechatProviders.getIfAvailable()).thenReturn(null);

        var availability =
                new AuthenticationMethodAvailabilityService(mail, sms, passkeys, wechatProviders);

        assertEquals(Set.of(PrimaryMethod.PASSWORD), availability.availableMethods());
    }

    @Test
    void providerReadinessInfrastructureFailuresAreNotTreatedAsUnconfigured() {
        var mail = mock(MailSender.class);
        when(mail.ready()).thenThrow(new IllegalStateException("database unavailable"));
        var availability =
                new AuthenticationMethodAvailabilityService(
                        mail,
                        mock(SmsSender.class),
                        mock(PasskeyPolicyPort.class),
                        mock(ObjectProvider.class));

        assertThrows(IllegalStateException.class, availability::availableMethods);
    }
}
