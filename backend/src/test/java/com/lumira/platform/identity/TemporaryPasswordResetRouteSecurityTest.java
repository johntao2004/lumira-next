package com.lumira.platform.identity;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.LoginResponse;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import com.lumira.platform.identity.api.TemporaryPasswordResetPort;
import com.lumira.plugins.directory.users.TemporaryPasswordResetController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the real route table and controller cache headers with Spring Security enabled. */
@WebMvcTest(
        controllers = {
            TemporaryPasswordResetController.class,
            ForcedPasswordChangeController.class
        })
@ContextConfiguration(classes = TemporaryPasswordResetRouteSecurityTest.TestWebConfiguration.class)
class TemporaryPasswordResetRouteSecurityTest {
    private static final String RESET_PERMISSION = "platform:users:reset-password";
    private static final UUID USER_ID = UUID.fromString("0c7455e8-c550-4828-9979-ec7ec422e50e");
    private static final UUID CHALLENGE = UUID.fromString("9ce6872a-96e2-4ffa-8642-c378d22f6e35");

    @Autowired MockMvc mvc;

    @MockitoBean JdbcClient jdbc;
    @MockitoBean LoginRateLimiter limiter;
    @MockitoBean LoginAudit loginAudit;
    @MockitoBean ClientAddressResolver addresses;
    @MockitoBean MaintenancePolicyPort maintenance;
    @MockitoBean AuthenticationPolicyService authenticationPolicies;
    @MockitoBean AuthenticationPolicyWriteLock authenticationPolicyWriteLock;
    @MockitoBean TransactionTemplate transactionTemplate;
    @MockitoBean SecondFactorLoginService secondFactor;
    @MockitoBean RolePreviewContextService rolePreviewContexts;
    @MockitoBean CaptchaProofAccess captchaProofAccess;
    @MockitoBean TemporaryPasswordResetService resets;

    @BeforeEach
    void installCurrentAuthorityFilterState() throws Exception {
        CurrentAuthorityFilterTestSupport.install(
                jdbc,
                authenticationPolicies,
                transactionTemplate,
                rolePreviewContexts,
                username ->
                        switch (username) {
                            case "resetter" -> List.of("platform:users:access", RESET_PERMISSION);
                            case "missing-page" -> List.of(RESET_PERMISSION);
                            case "wrong-authority" ->
                                    List.of("platform:users:access", "platform:users:update");
                            default -> List.of();
                        });
    }

    @Test
    void resetRequiresDedicatedAuthorityAndCsrfBeforeReturningNoStoreSecret() throws Exception {
        when(resets.reset(USER_ID, 9L, "resetter"))
                .thenReturn(
                        new TemporaryPasswordResetPort.ResetResult(
                                "OneTime-Secret!8247", Instant.parse("2026-10-11T10:30:00Z")));

        mvc.perform(
                        post("/api/platform/users/{id}/password-reset", USER_ID)
                                .with(user("missing-page"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":9}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(resets);

        mvc.perform(
                        post("/api/platform/users/{id}/password-reset", USER_ID)
                                .with(user("wrong-authority"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":9}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(resets);

        mvc.perform(
                        post("/api/platform/users/{id}/password-reset", USER_ID)
                                .with(user("resetter"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":9}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(resets);

        mvc.perform(
                        post("/api/platform/users/{id}/password-reset", USER_ID)
                                .with(user("resetter"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":9}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
                .andExpect(header().string("Pragma", "no-cache"));
        verify(resets).reset(USER_ID, 9L, "resetter");
    }

    @Test
    void passwordChangeIsPublicForThePendingSessionButStillRequiresCsrfAndNoStore()
            throws Exception {
        mvc.perform(
                        post("/api/auth/login/password-change")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"challengeId\":\""
                                                + CHALLENGE
                                                + "\",\"newPassword\":\"W!m8Q$zR4#vP7k2X\",\"confirmPassword\":\"W!m8Q$zR4#vP7k2X\"}"))
                .andExpect(status().isForbidden());
        verify(resets, never())
                .changePasswordAndContinue(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        when(resets.changePasswordAndContinue(
                        org.mockito.ArgumentMatchers.eq(CHALLENGE),
                        org.mockito.ArgumentMatchers.eq("W!m8Q$zR4#vP7k2X"),
                        org.mockito.ArgumentMatchers.eq("W!m8Q$zR4#vP7k2X"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(LoginResponse.complete());

        mvc.perform(
                        post("/api/auth/login/password-change")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"challengeId\":\""
                                                + CHALLENGE
                                                + "\",\"newPassword\":\"W!m8Q$zR4#vP7k2X\",\"confirmPassword\":\"W!m8Q$zR4#vP7k2X\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.authenticated")
                                .value(true))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.state")
                                .value("COMPLETE"))
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
                .andExpect(header().string("Pragma", "no-cache"));
        verify(resets)
                .changePasswordAndContinue(
                        org.mockito.ArgumentMatchers.eq(CHALLENGE),
                        org.mockito.ArgumentMatchers.eq("W!m8Q$zR4#vP7k2X"),
                        org.mockito.ArgumentMatchers.eq("W!m8Q$zR4#vP7k2X"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @Import({
        SecurityConfiguration.class,
        TemporaryPasswordResetController.class,
        ForcedPasswordChangeController.class
    })
    static class TestWebConfiguration {
        @org.springframework.context.annotation.Bean
        com.lumira.platform.common.PluginAuthorizationPort directoryAuthorization()
                throws Exception {
            return com.lumira.platform.plugins.PluginManifestAuthorizationTestSupport.forPlugins(
                    "directory");
        }
    }
}
