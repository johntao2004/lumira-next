package com.lumira.platform.identity;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AiModelRouteSecurityTest.RouteProbeController.class)
@ContextConfiguration(classes = AiModelRouteSecurityTest.TestWebConfiguration.class)
class AiModelRouteSecurityTest {
    private static final UUID PROVIDER_ID = UUID.fromString("f3fb35ef-b869-4e16-a31a-a4c08f70c335");

    @Autowired MockMvc mvc;

    @MockitoBean JdbcClient jdbc;
    @MockitoBean LoginRateLimiter limiter;
    @MockitoBean LoginAudit loginAudit;
    @MockitoBean CaptchaProofAccess captchaProofAccess;
    @MockitoBean ClientAddressResolver addresses;
    @MockitoBean MaintenancePolicyPort maintenance;
    @MockitoBean AuthenticationPolicyService authenticationPolicies;
    @MockitoBean AuthenticationPolicyWriteLock authenticationPolicyWriteLock;
    @MockitoBean TransactionTemplate transactionTemplate;
    @MockitoBean SecondFactorLoginService secondFactor;
    @MockitoBean RolePreviewContextService rolePreviewContexts;
    @MockitoBean RouteProbe routeProbe;

    @BeforeEach
    void stubCurrentAuthorities() throws Exception {
        CurrentAuthorityFilterTestSupport.install(
                jdbc,
                authenticationPolicies,
                transactionTemplate,
                rolePreviewContexts,
                username ->
                        switch (username) {
                            case "operator" ->
                                    List.of(
                                            "platform:ai-console:access",
                                            "platform:ai-console:update-user");
                            case "console-reader" -> List.of("platform:ai-console:access");
                            case "models-reader" -> List.of("platform:ai-models:access");
                            case "models-tester" ->
                                    List.of("platform:ai-models:access", "platform:ai-models:test");
                            default -> List.of("platform:settings:read");
                        });
    }

    @Test
    void aiModelsPageCanReachProviderModelListRoute() throws Exception {
        mvc.perform(
                        get("/api/platform/ai/providers/{id}/models", PROVIDER_ID)
                                .with(user("models-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isOk());

        verify(routeProbe).listModels(PROVIDER_ID);
    }

    @Test
    void aiModelsTestActionCanReachDiscoveryRouteWithCsrf() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/providers/discover-models")
                                .with(user("models-tester"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk());

        verify(routeProbe).discoverModels();
    }

    @Test
    void aiConsolePageAndCapabilityCanReachChatWithoutAnOperationPayload() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/chat")
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk());

        verify(routeProbe).chat();
    }

    @Test
    void aiConsolePageOnlyGrantCannotReachChat() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/chat")
                                .with(user("console-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(routeProbe);
    }

    @Test
    void pageOnlyAiModelsGrantCannotReachDiscoveryAndControllerIsNotInvoked() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/providers/discover-models")
                                .with(user("models-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(routeProbe);
    }

    @Test
    void discoveryStillRequiresCsrfAndUnmatchedAiRoutesRemainDenied() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/providers/discover-models")
                                .with(user("writer"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/platform/ai/unmatched")
                                .with(user("writer"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());

        verify(routeProbe, never()).discoverModels();
        verifyNoInteractions(routeProbe);
    }

    @Test
    void cancellationRequiresAiConsolePageAndAnOperationActionAndCsrf() throws Exception {
        mvc.perform(
                        delete("/api/platform/ai/actions/{id}", PROVIDER_ID)
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        delete("/api/platform/ai/actions/{id}", PROVIDER_ID)
                                .with(user("reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        delete("/api/platform/ai/actions/{id}", PROVIDER_ID)
                                .with(user("console-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routeProbe);
        mvc.perform(
                        delete("/api/platform/ai/actions/{id}", PROVIDER_ID)
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf()))
                .andExpect(status().isOk());
        verify(routeProbe).cancelAction(PROVIDER_ID);
    }

    @Test
    void attachmentReadRequiresAiConsolePageAccess() throws Exception {
        mvc.perform(
                        get("/api/platform/ai/attachments/{id}", PROVIDER_ID)
                                .with(user("models-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routeProbe);
        mvc.perform(
                        get("/api/platform/ai/attachments/{id}", PROVIDER_ID)
                                .with(user("console-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isOk());
        verify(routeProbe).readAttachment(PROVIDER_ID);
    }

    @RestController
    static class RouteProbeController {
        private final RouteProbe probe;

        RouteProbeController(RouteProbe probe) {
            this.probe = probe;
        }

        @GetMapping("/api/platform/ai/providers/{id}/models")
        String listModels(@PathVariable UUID id) {
            probe.listModels(id);
            return "ok";
        }

        @PostMapping("/api/platform/ai/providers/discover-models")
        String discoverModels(@RequestBody java.util.Map<String, Object> ignored) {
            probe.discoverModels();
            return "ok";
        }

        @DeleteMapping("/api/platform/ai/actions/{id}")
        String cancelAction(@PathVariable UUID id) {
            probe.cancelAction(id);
            return "ok";
        }

        @PostMapping("/api/platform/ai/chat")
        String chat(@RequestBody java.util.Map<String, Object> ignored) {
            probe.chat();
            return "ok";
        }

        @GetMapping("/api/platform/ai/attachments/{id}")
        String readAttachment(@PathVariable UUID id) {
            probe.readAttachment(id);
            return "ok";
        }
    }

    interface RouteProbe {
        void listModels(UUID providerId);

        void discoverModels();

        void cancelAction(UUID id);

        void readAttachment(UUID id);

        void chat();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @Import({SecurityConfiguration.class, RouteProbeController.class})
    static class TestWebConfiguration {}
}
