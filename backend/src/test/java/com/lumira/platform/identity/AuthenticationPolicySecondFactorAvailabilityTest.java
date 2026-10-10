package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthenticationPolicySecondFactorAvailabilityTest {
    private PluginExtensionsPort plugins;
    private List<SecondFactorProvider> providers;
    private AuthenticationPolicyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        plugins = mock(PluginExtensionsPort.class);
        providers = List.of();
        when(plugins.<SecondFactorProvider, Object>extensions(
                        eq(SecondFactorProvider.class), any()))
                .thenAnswer(
                        invocation -> {
                            Function<SecondFactorProvider, Object> mapper =
                                    invocation.getArgument(1);
                            return providers.stream().map(mapper).toList();
                        });
        ObjectProvider<AuthenticationCapabilityContributor> contributors =
                (ObjectProvider<AuthenticationCapabilityContributor>) mock(ObjectProvider.class);
        service =
                new AuthenticationPolicyService(
                        mock(JdbcClient.class),
                        mock(GlobalAuthenticationPolicyPort.class),
                        mock(RoleAuthenticationPolicyPort.class),
                        mock(PasskeyPolicyPort.class),
                        plugins,
                        contributors);
    }

    @Test
    void reportsAvailabilityOnlyForExactlyOneAvailableMfaProvider() {
        providers = List.of(provider("mfa", false, null));
        assertFalse(service.secondFactorAvailable());

        providers = List.of(provider("mfa", true, null));
        assertFalse(service.secondFactorAvailable());

        providers = List.of(completeProvider("mfa", true, null));
        assertTrue(service.secondFactorAvailable());

        providers =
                List.of(completeProvider("mfa", true, null), completeProvider("mfa", true, null));
        assertFalse(service.secondFactorAvailable());
    }

    @Test
    void readsStatusInsideTheExtensionCallbackAndFiltersUnavailableOrUnrelatedProviders() {
        UUID userId = UUID.randomUUID();
        var unavailable = provider("mfa", false, new SecondFactorProvider.AccountStatus(false, 0));
        var unrelated = provider("other", true, new SecondFactorProvider.AccountStatus(false, 0));
        var available = provider("mfa", true, new SecondFactorProvider.AccountStatus(true, 4));
        providers = List.of(unavailable, unrelated, available);

        assertEquals(
                new AuthenticationPolicyService.AuthenticatorStatus(true, true, 4),
                service.authenticatorStatus(userId));
        assertEquals(0, unavailable.statusCalls.get());
        assertEquals(0, unrelated.statusCalls.get());
        assertEquals(1, available.statusCalls.get());
        assertEquals(userId, available.lastUserId);
    }

    @Test
    void returnsUnavailableWhenNoOrMultipleProvidersCanReportStatus() {
        UUID userId = UUID.randomUUID();
        assertEquals(
                new AuthenticationPolicyService.AuthenticatorStatus(false, null, null),
                service.authenticatorStatus(userId));

        providers =
                List.of(
                        provider("mfa", true, new SecondFactorProvider.AccountStatus(true, 3)),
                        provider("mfa", true, new SecondFactorProvider.AccountStatus(true, 2)));
        assertEquals(
                new AuthenticationPolicyService.AuthenticatorStatus(false, null, null),
                service.authenticatorStatus(userId));
    }

    private static TestProvider provider(
            String id, boolean available, SecondFactorProvider.AccountStatus status) {
        return new TestProvider(id, available, status, false);
    }

    private static TestProvider completeProvider(
            String id, boolean available, SecondFactorProvider.AccountStatus status) {
        return new TestProvider(id, available, status, true);
    }

    private static final class TestProvider implements SecondFactorProvider {
        private final String id;
        private final boolean available;
        private final AccountStatus status;
        private final boolean restrictedRecovery;
        private final AtomicInteger statusCalls = new AtomicInteger();
        private UUID lastUserId;

        private TestProvider(
                String id, boolean available, AccountStatus status, boolean restrictedRecovery) {
            this.id = id;
            this.available = available;
            this.status = status;
            this.restrictedRecovery = restrictedRecovery;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean verify(UUID userId, String code) {
            return false;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean supportsRestrictedEnrollmentAndRecovery() {
            return restrictedRecovery;
        }

        @Override
        public AccountStatus status(UUID userId) {
            statusCalls.incrementAndGet();
            lastUserId = userId;
            return status;
        }
    }
}
