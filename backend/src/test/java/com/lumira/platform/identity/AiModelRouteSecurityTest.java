package com.lumira.platform.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
    @MockitoBean ClientAddressResolver addresses;
    @MockitoBean MaintenancePolicyPort maintenance;
    @MockitoBean RouteProbe routeProbe;

    private final AtomicReference<String> currentUsername = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void stubCurrentAuthorities() {
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(
                        "SELECT a.authority FROM platform_user u LEFT JOIN platform_effective_authority a ON a.user_id=u.id WHERE u.username=:name AND u.enabled=true"))
                .thenReturn(statement);
        when(statement.param(eq("name"), any()))
                .thenAnswer(
                        invocation -> {
                            currentUsername.set(invocation.getArgument(1));
                            return statement;
                        });
        doAnswer(
                        invocation -> {
                            RowCallbackHandler handler = invocation.getArgument(0);
                            var authorities =
                                    currentUsername.get().equals("operator")
                                            ? List.of("platform:users:read")
                                            : currentUsername.get().equals("reader")
                                                    ? List.of("platform:settings:read")
                                                    : List.of(
                                                            "platform:settings:read",
                                                            "platform:settings:write");
                            for (String authority : authorities) {
                                var row = mock(java.sql.ResultSet.class);
                                when(row.getString(1)).thenReturn(authority);
                                handler.processRow(row);
                            }
                            return null;
                        })
                .when(statement)
                .query(any(RowCallbackHandler.class));
    }

    @Test
    void settingsReaderCanReachModelListRoute() throws Exception {
        mvc.perform(get("/api/platform/ai/providers/{id}/models", PROVIDER_ID).with(user("reader")))
                .andExpect(status().isOk());

        verify(routeProbe).listModels(PROVIDER_ID);
    }

    @Test
    void settingsWriterCanReachDiscoveryRouteWithCsrf() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/providers/discover-models")
                                .with(user("writer"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk());

        verify(routeProbe).discoverModels();
    }

    @Test
    void settingsReaderCannotReachDiscoveryAndControllerIsNotInvoked() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/providers/discover-models")
                                .with(user("reader"))
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
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/ai/unmatched").with(user("writer")))
                .andExpect(status().isForbidden());

        verify(routeProbe, never()).discoverModels();
        verifyNoInteractions(routeProbe);
    }

    @Test
    void cancellationRequiresUserReadPermissionAndCsrf() throws Exception {
        mvc.perform(delete("/api/platform/ai/actions/{id}", PROVIDER_ID).with(user("operator")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        delete("/api/platform/ai/actions/{id}", PROVIDER_ID)
                                .with(user("reader"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(routeProbe);
        mvc.perform(
                        delete("/api/platform/ai/actions/{id}", PROVIDER_ID)
                                .with(user("operator"))
                                .with(csrf()))
                .andExpect(status().isOk());
        verify(routeProbe).cancelAction(PROVIDER_ID);
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
    }

    interface RouteProbe {
        void listModels(UUID providerId);

        void discoverModels();

        void cancelAction(UUID id);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @Import({SecurityConfiguration.class, RouteProbeController.class})
    static class TestWebConfiguration {}
}
