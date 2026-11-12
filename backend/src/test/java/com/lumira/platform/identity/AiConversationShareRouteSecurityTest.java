package com.lumira.platform.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.ai.AiConversationShareController;
import com.lumira.platform.ai.AiConversationShareService;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@WebMvcTest(controllers = AiConversationShareController.class)
@ContextConfiguration(classes = AiConversationShareRouteSecurityTest.TestWebConfiguration.class)
class AiConversationShareRouteSecurityTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String CREATED_TOKEN = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-10-17T01:02:03Z");
    private static final UUID SHARE_ID = UUID.fromString("b1136629-cdd3-4536-80b7-7f65c1134215");

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
    @MockitoBean AiConversationShareService shares;

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
                            default -> List.of("platform:settings:read");
                        });
    }

    @Test
    void conversationCanBeSharedWithExistingChatPrivilegeAndCsrf() throws Exception {
        when(shares.create(any(JsonNode.class), eq("operator")))
                .thenReturn(
                        new AiConversationShareService.CreateResult(
                                SHARE_ID, CREATED_TOKEN, "/share/ai/" + CREATED_TOKEN, CREATED_AT));

        mvc.perform(
                        post("/api/platform/ai/shares")
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\"Support\",\"messages\":[{\"role\":\"user\",\"content\":\"Help\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SHARE_ID.toString()))
                .andExpect(jsonPath("$.token").value(CREATED_TOKEN))
                .andExpect(jsonPath("$.path").value("/share/ai/" + CREATED_TOKEN))
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));

        verify(shares).create(any(JsonNode.class), eq("operator"));
    }

    @Test
    void updatesRequireChatPrivilegeAuthenticationAndCsrf() throws Exception {
        mvc.perform(
                        put("/api/platform/ai/shares/" + SHARE_ID)
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"));
        verify(shares).update(eq(SHARE_ID), any(JsonNode.class), eq("operator"));
        mvc.perform(
                        put("/api/platform/ai/shares/" + SHARE_ID)
                                .with(user("operator"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put("/api/platform/ai/shares/" + SHARE_ID)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        put("/api/platform/ai/shares/" + SHARE_ID)
                                .with(user("console-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatorListUsesOneBasedPageResultAndRequiresChatPrivilege() throws Exception {
        when(shares.list(any(PageRequest.class), eq("operator")))
                .thenReturn(
                        new PageResult<>(
                                List.of(
                                        new AiConversationShareService.ShareSummary(
                                                SHARE_ID, "Support", CREATED_AT)),
                                1,
                                1,
                                20));

        mvc.perform(
                        get("/api/platform/ai/shares")
                                .param("page", "1")
                                .param("pageSize", "20")
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(SHARE_ID.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Support"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"));

        mvc.perform(
                        get("/api/platform/ai/shares")
                                .with(user("console-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/ai/shares")).andExpect(status().isUnauthorized());
        verify(shares).list(any(PageRequest.class), eq("operator"));
    }

    @Test
    void revokeRequiresChatPrivilegeAndCsrf() throws Exception {
        mvc.perform(
                        delete("/api/platform/ai/shares/" + SHARE_ID)
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(
                        delete("/api/platform/ai/shares/" + SHARE_ID)
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/platform/ai/shares/" + SHARE_ID).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        delete("/api/platform/ai/shares/" + SHARE_ID)
                                .with(user("console-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf()))
                .andExpect(status().isForbidden());
        verify(shares).revoke(SHARE_ID, "operator");
    }

    @Test
    void shareCreationRequiresChatActionAndCsrf() throws Exception {
        mvc.perform(
                        post("/api/platform/ai/shares")
                                .with(user("console-reader"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(
                        post("/api/platform/ai/shares")
                                .with(user("operator"))
                                .header(
                                        RolePreviewContextService.REVISION_HEADER,
                                        CurrentAuthorityFilterTestSupport.CONTEXT_REVISION)
                                .session(CurrentAuthorityFilterTestSupport.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(
                        post("/api/platform/ai/shares")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(shares);
    }

    @Test
    void publicSnapshotCanBeReadAnonymouslyWithPrivacyHeaders() throws Exception {
        when(shares.read(TOKEN))
                .thenReturn(
                        new AiConversationShareService.SharedConversation(
                                "Support",
                                List.of(
                                        new AiConversationShareService.Message(
                                                "assistant", "Answer")),
                                CREATED_AT));

        mvc.perform(get("/api/public/ai/shares/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.title").value("Support"))
                .andExpect(jsonPath("$.messages[0].role").value("assistant"))
                .andExpect(jsonPath("$.messages[0].content").value("Answer"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));

        verify(shares).read(TOKEN);
    }

    @Test
    void missingPublicSnapshotStillHasPrivacyHeaders() throws Exception {
        when(shares.read(TOKEN))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "aiShareNotFound"));

        mvc.perform(get("/api/public/ai/shares/" + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @Import({SecurityConfiguration.class, AiConversationShareController.class})
    static class TestWebConfiguration {
        @org.springframework.context.annotation.Bean
        com.lumira.platform.common.PluginAuthorizationPort aiAuthorization() {
            return com.lumira.platform.plugins.PluginManifestAuthorizationTestSupport.forPlugins(
                    "ai");
        }
    }
}
