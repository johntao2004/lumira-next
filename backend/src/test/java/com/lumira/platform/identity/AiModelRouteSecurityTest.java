package com.lumira.platform.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AiModelRouteSecurityTest.RouteProbeController.class)
@ContextConfiguration(classes = AiModelRouteSecurityTest.TestWebConfiguration.class)
class AiModelRouteSecurityTest {
    private static final UUID USER_ID = UUID.fromString("6d6144c4-1e60-4a82-96c4-081997dcc001");
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
        var userStatement = mock(JdbcClient.StatementSpec.class);
        var authorityStatement = mock(JdbcClient.StatementSpec.class);
        var userQuery = mock(JdbcClient.MappedQuerySpec.class);
        var authorityQuery = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql("SELECT id FROM platform_user WHERE username=:name AND enabled=true"))
            .thenReturn(userStatement);
        when(userStatement.param(eq("name"), any())).thenAnswer(invocation -> {
            currentUsername.set(invocation.getArgument(1));
            return userStatement;
        });
        when(userStatement.query(UUID.class)).thenReturn(userQuery);
        when(userQuery.optional()).thenReturn(Optional.of(USER_ID));

        when(jdbc.sql("SELECT authority FROM platform_effective_authority WHERE user_id=:id"))
            .thenReturn(authorityStatement);
        when(authorityStatement.param("id", USER_ID)).thenReturn(authorityStatement);
        when(authorityStatement.query(String.class)).thenReturn(authorityQuery);
        when(authorityQuery.list()).thenAnswer(invocation -> currentUsername.get().equals("reader")
            ? List.of("platform:settings:read")
            : List.of("platform:settings:read", "platform:settings:write"));
    }

    @Test
    void settingsReaderCanReachModelListRoute() throws Exception {
        mvc.perform(get("/api/platform/ai/providers/{id}/models", PROVIDER_ID)
                .with(user("reader")))
            .andExpect(status().isOk());

        verify(routeProbe).listModels(PROVIDER_ID);
    }

    @Test
    void settingsWriterCanReachDiscoveryRouteWithCsrf() throws Exception {
        mvc.perform(post("/api/platform/ai/providers/discover-models")
                .with(user("writer"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());

        verify(routeProbe).discoverModels();
    }

    @Test
    void settingsReaderCannotReachDiscoveryAndControllerIsNotInvoked() throws Exception {
        mvc.perform(post("/api/platform/ai/providers/discover-models")
                .with(user("reader"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(routeProbe);
    }

    @Test
    void discoveryStillRequiresCsrfAndUnmatchedAiRoutesRemainDenied() throws Exception {
        mvc.perform(post("/api/platform/ai/providers/discover-models")
                .with(user("writer"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/ai/unmatched").with(user("writer")))
            .andExpect(status().isForbidden());

        verify(routeProbe, never()).discoverModels();
        verifyNoInteractions(routeProbe);
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
    }

    interface RouteProbe {
        void listModels(UUID providerId);
        void discoverModels();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @Import({SecurityConfiguration.class, RouteProbeController.class})
    static class TestWebConfiguration {}
}
