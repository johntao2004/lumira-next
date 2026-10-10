package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthenticationPublicMethodsTest {
    @Test
    void loginCodeCapabilityTracksGloballyEnabledOtpMethods() {
        var global = mock(GlobalAuthenticationPolicyPort.class);
        var passkeys = mock(PasskeyPolicyPort.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthenticationCapabilityContributor> contributorProvider =
                mock(ObjectProvider.class);
        var contributor = mock(AuthenticationCapabilityContributor.class);
        var publicRegistration = mock(PublicRegistrationService.class);
        var registrationContributor = new RegistrationCapabilityContributor(publicRegistration);
        when(passkeys.currentPolicy())
                .thenReturn(
                        new PasskeyPolicyPort.Policy(
                                false, false, false, null, null, List.of(), 60));
        when(contributor.authenticationCapabilities())
                .thenReturn(
                        new AuthenticationCapabilityContributor.Contribution(
                                EnumSet.of(PrimaryMethod.EMAIL_OTP, PrimaryMethod.WECHAT),
                                true,
                                true,
                                false,
                                false,
                                null,
                                null));
        when(contributorProvider.orderedStream())
                .thenAnswer(invocation -> Stream.of(contributor, registrationContributor));

        var service =
                new AuthenticationPolicyService(
                        mock(JdbcClient.class),
                        global,
                        mock(RoleAuthenticationPolicyPort.class),
                        passkeys,
                        mock(PluginExtensionsPort.class),
                        contributorProvider);
        var revision = UUID.randomUUID();

        when(global.current())
                .thenReturn(
                        new GlobalAuthenticationPolicyPort.Policy(
                                1, revision, EnumSet.of(PrimaryMethod.PASSWORD), false));
        when(publicRegistration.config()).thenReturn(registrationConfig(false, List.of()));
        var disabled = service.publicMethods();
        assertFalse(disabled.capabilities().loginCode());
        assertFalse(disabled.methods().contains(PrimaryMethod.EMAIL_OTP));
        assertTrue(disabled.registration().supported());
        assertFalse(disabled.registration().available());
        assertTrue(disabled.registration().channels().isEmpty());
        assertEquals("/user/register", disabled.registration().path());

        when(publicRegistration.config())
                .thenReturn(registrationConfig(true, List.of("SMS", "EMAIL", "EMAIL", "WECHAT")));
        when(global.current())
                .thenReturn(
                        new GlobalAuthenticationPolicyPort.Policy(
                                2,
                                UUID.randomUUID(),
                                EnumSet.of(PrimaryMethod.PASSWORD, PrimaryMethod.EMAIL_OTP),
                                false));
        var enabled = service.publicMethods();
        assertTrue(enabled.capabilities().loginCode());
        assertTrue(enabled.methods().contains(PrimaryMethod.EMAIL_OTP));
        assertFalse(enabled.capabilities().wechat());
        assertTrue(enabled.registration().supported());
        assertTrue(enabled.registration().available());
        assertEquals(List.of("EMAIL", "SMS"), enabled.registration().channels());
        assertEquals("/user/register", enabled.registration().path());
        var registrationMethodsA = registrationContributor.accountMethods(UUID.randomUUID());
        var registrationMethodsB = registrationContributor.accountMethods(UUID.randomUUID());
        assertTrue(registrationMethodsA.usableMethods().isEmpty());
        assertEquals(Set.of(), registrationMethodsA.usableMethods());
        assertEquals("registration-capability:v1", registrationMethodsA.authenticationRevision());
        assertEquals(
                registrationMethodsA.authenticationRevision(),
                registrationMethodsB.authenticationRevision());

        when(global.current())
                .thenReturn(
                        new GlobalAuthenticationPolicyPort.Policy(
                                3,
                                UUID.randomUUID(),
                                EnumSet.of(
                                        PrimaryMethod.PASSWORD,
                                        PrimaryMethod.EMAIL_OTP,
                                        PrimaryMethod.WECHAT),
                                false));
        var wechatEnabled = service.publicMethods();
        assertTrue(wechatEnabled.capabilities().wechat());
        assertTrue(wechatEnabled.methods().contains(PrimaryMethod.WECHAT));
    }

    private static PublicRegistrationService.Config registrationConfig(
            boolean available, List<String> channels) {
        return new PublicRegistrationService.Config(
                available,
                available ? null : "REGISTRATION_CLOSED",
                channels,
                7,
                new PublicRegistrationService.PasswordRules(12, 256, true, true, false, true),
                new PublicRegistrationService.CodePolicy(300, 60),
                new PublicRegistrationService.AgreementsView(3, "terms", "privacy"));
    }
}
