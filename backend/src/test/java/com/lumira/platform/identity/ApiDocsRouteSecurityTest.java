package com.lumira.platform.identity;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Checks the API schema endpoint's login, global-scope and cache controls. */
@WebMvcTest(controllers = ApiDocsRouteSecurityTest.Probe.class)
@ContextConfiguration(classes = ApiDocsRouteSecurityTest.Config.class)
class ApiDocsRouteSecurityTest {
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
    @MockitoBean AdministrationPort administration;
    @MockitoBean RolePreviewContextService rolePreviewContexts;
    @MockitoBean com.lumira.platform.common.PluginAuthorizationPort plugins;

    @BeforeEach
    void installCurrentAuthorityContext() throws Exception {
        when(plugins.authorize(
                        org.mockito.ArgumentMatchers.eq("GET"),
                        org.mockito.ArgumentMatchers.eq("/api/platform/api-docs"),
                        org.mockito.ArgumentMatchers.anySet(),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenAnswer(
                        invocation ->
                                ((java.util.Set<?>) invocation.getArgument(2))
                                        .contains(ApiDocsAccessPolicy.PERMISSION));
        CurrentAuthorityFilterTestSupport.install(
                jdbc,
                authenticationPolicies,
                transactionTemplate,
                rolePreviewContexts,
                username ->
                        switch (username) {
                            case "auditor", "admin" -> List.of(ApiDocsAccessPolicy.PERMISSION);
                            default -> List.of("platform:settings:read");
                        });
    }

    @Test
    void unavailableMonitoringPluginDeniesEvenTheGlobalAdministrator() throws Exception {
        when(plugins.authorize(
                        org.mockito.ArgumentMatchers.eq("GET"),
                        org.mockito.ArgumentMatchers.eq("/api/platform/api-docs"),
                        org.mockito.ArgumentMatchers.anySet(),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(false);
        mvc.perform(
                        get("/api/platform/api-docs")
                                .with(
                                        user("admin")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                ApiDocsAccessPolicy.PERMISSION)))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(administration);
    }

    @Test
    void anonymousRequestIsUnauthorizedAndNotCacheable() throws Exception {
        mvc.perform(get("/api/platform/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(
                        header().string(
                                        "Cache-Control",
                                        "no-cache, no-store, max-age=0, must-revalidate"));
        verifyNoInteractions(administration);
    }

    @Test
    void authenticatedUserWithoutAuditPermissionIsForbiddenWithoutGlobalLookup() throws Exception {
        mvc.perform(
                        get("/api/platform/api-docs")
                                .with(
                                        user("reader")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "platform:settings:read")))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(administration);
    }

    @Test
    void auditPermissionWithoutGlobalScopeIsForbidden() throws Exception {
        when(administration.hasGlobal("auditor", ApiDocsAccessPolicy.PERMISSION)).thenReturn(false);

        mvc.perform(
                        get("/api/platform/api-docs")
                                .with(
                                        user("auditor")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                ApiDocsAccessPolicy.PERMISSION)))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());

        verify(administration).hasGlobal("auditor", ApiDocsAccessPolicy.PERMISSION);
    }

    @Test
    void globalAuditPermissionCanReadTheSpec() throws Exception {
        when(administration.hasGlobal("admin", ApiDocsAccessPolicy.PERMISSION)).thenReturn(true);

        mvc.perform(
                        get("/api/platform/api-docs")
                                .with(
                                        user("admin")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                ApiDocsAccessPolicy.PERMISSION)))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Cache-Control",
                                        "no-cache, no-store, max-age=0, must-revalidate"));

        verify(administration).hasGlobal("admin", ApiDocsAccessPolicy.PERMISSION);
    }

    @Test
    void defaultSpringdocAndSwaggerPathsStayClosed() throws Exception {
        when(administration.hasGlobal("admin", ApiDocsAccessPolicy.PERMISSION)).thenReturn(true);
        var admin =
                user("admin")
                        .authorities(new SimpleGrantedAuthority(ApiDocsAccessPolicy.PERMISSION));

        mvc.perform(
                        get("/v3/api-docs")
                                .with(admin)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/v3/api-docs.yaml")
                                .with(admin)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/swagger-ui/index.html")
                                .with(admin)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/platform/api-docs.yaml")
                                .with(admin)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/platform/api-docs/openapi")
                                .with(admin)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/platform/api-docs")
                                .with(admin)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class Probe {
        @GetMapping("/api/platform/api-docs")
        String spec() {
            return "{}";
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiDocsAccessPolicy.class, Probe.class})
    static class Config {}
}
