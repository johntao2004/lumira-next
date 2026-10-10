package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.web.GlobalContentPolicyAdvice;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class GlobalContentPolicyAdviceTest {
    record Body(
            String title,
            String detailHtml,
            String password,
            String apiKey,
            Map<String, String> values,
            List<Map<String, String>> messages) {}

    static class Controller {
        public void submit(Body body) {}
    }

    @ContentPolicyExempt
    static class PolicyController {
        public void submit(Body body) {}
    }

    ContentPolicyPort policy;
    GlobalContentPolicyAdvice advice;

    @BeforeEach
    void setup() {
        policy = mock(ContentPolicyPort.class);
        ObjectProvider<ContentPolicyPort> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(policy));
        when(policy.evaluate(anyString(), anyString(), anyString()))
                .thenReturn(new ContentPolicyPort.ScanResult(false));
        advice = new GlobalContentPolicyAdvice(provider, JsonMapper.builder().build());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated("qa", "", List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    MethodParameter parameter(Class<?> type) throws Exception {
        return new MethodParameter(type.getMethod("submit", Body.class), 0);
    }

    @Test
    void readsVisibleHtmlAndNestedBusinessTextButNeverCredentials() throws Exception {
        var input =
                new Body(
                        "标题",
                        "<p>lumi<b>ra</b>&#113;a</p>",
                        "secretword",
                        "secretword",
                        Map.of("bio", "介绍", "password", "secretword"),
                        List.of(Map.of("content", "聊天正文", "role", "user")));
        assertSame(
                input,
                advice.afterBodyRead(input, null, parameter(Controller.class), Body.class, null));
        verify(policy).evaluate("qa", "标题", "Controller.submit:title");
        verify(policy).evaluate("qa", "介绍", "Controller.submit:values.bio");
        verify(policy).evaluate("qa", "聊天正文", "Controller.submit:messages.content");
        verify(policy)
                .evaluate(
                        eq("qa"),
                        argThat(s -> s.strip().equals("lumiraqa")),
                        eq("Controller.submit:detailHtml"));
        verify(policy, never()).evaluate(anyString(), eq("secretword"), anyString());
    }

    @Test
    void readsSettingsAndNavigationTextWithoutScanningIdentifiersOrCredentials() throws Exception {
        var input =
                Map.of(
                        "data",
                        Map.of("websiteName", "站点名称", "textLines", List.of("水印内容")),
                        "labelDefault",
                        "菜单文字",
                        "credential",
                        Map.of("value", "secretword"),
                        "messageKey",
                        "secretword",
                        "baseUrl",
                        "secretword",
                        "changeReason",
                        "调整原因");
        advice.afterBodyRead(input, null, parameter(Controller.class), Map.class, null);
        verify(policy).evaluate("qa", "站点名称", "Controller.submit:data.websiteName");
        verify(policy).evaluate("qa", "水印内容", "Controller.submit:data.textLines");
        verify(policy).evaluate("qa", "菜单文字", "Controller.submit:labelDefault");
        verify(policy).evaluate("qa", "调整原因", "Controller.submit:changeReason");
        verify(policy, never()).evaluate(anyString(), eq("secretword"), anyString());
    }

    @Test
    void rejectsBeforeBusinessHandlerAndReportsStableCode() throws Exception {
        when(policy.evaluate(anyString(), eq("blocked"), anyString()))
                .thenReturn(new ContentPolicyPort.ScanResult(true));
        var e =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                advice.afterBodyRead(
                                        new Body("blocked", null, null, null, null, null),
                                        null,
                                        parameter(Controller.class),
                                        Body.class,
                                        null));
        assertEquals(400, e.getStatusCode().value());
        assertEquals("sensitiveContent", e.getReason());
    }

    @Test
    void configurationEndpointIsExplicitlyExempt() throws Exception {
        assertFalse(advice.supports(parameter(PolicyController.class), Body.class, null));
        assertTrue(advice.supports(parameter(Controller.class), Body.class, null));
    }

    @Test
    void anonymousAuthenticationDoesNotInvokeActorPolicy() throws Exception {
        SecurityContextHolder.clearContext();
        advice.afterBodyRead(
                new Body("test", null, null, null, null, null),
                null,
                parameter(Controller.class),
                Body.class,
                null);
        verifyNoInteractions(policy);
    }
}
