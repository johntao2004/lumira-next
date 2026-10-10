package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import java.util.*;
import org.junit.jupiter.api.*;

class RoleContentPolicyServiceTest {
    RoleContentPolicyRepository policies;
    SensitiveWordRepository words;
    RoleContentPolicyService service;
    UUID role = UUID.randomUUID();

    @BeforeEach
    void setup() {
        policies = mock(RoleContentPolicyRepository.class);
        words = mock(SensitiveWordRepository.class);
        when(words.matcherSnapshot(anyLong()))
                .thenAnswer(
                        i ->
                                new SensitiveWordRepository.MatcherSnapshot(
                                        1,
                                        i.getArgument(0, Long.class) == 1
                                                ? List.of()
                                                : List.of(
                                                        new SensitiveWordRepository.ActiveEntry(
                                                                "blocked", "blocked", "LOW",
                                                                "BLOCK"),
                                                        new SensitiveWordRepository.ActiveEntry(
                                                                "logged",
                                                                "logged",
                                                                "HIGH",
                                                                "LOG_ONLY"))));
        service =
                new RoleContentPolicyService(
                        policies,
                        words,
                        mock(SensitiveWordService.class),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        id -> true);
    }

    RoleContentPolicyRepository.Policy policy(String mode) {
        return new RoleContentPolicyRepository.Policy(role, "QA", "QA", true, mode, 1);
    }

    @Test
    void oneOnRoleOverridesOtherOffRoles() {
        when(policies.forActor("qa")).thenReturn(List.of(policy("OFF"), policy("ON")));
        assertTrue(service.evaluate("qa", "blocked", "profile").blocked());
        verify(policies).record(eq("qa"), eq("profile"), eq(true), eq(1), anyList());
    }

    @Test
    void allOffRolesAllowSameSharedWordWithoutLog() {
        when(policies.forActor("qa")).thenReturn(List.of(policy("OFF")));
        assertFalse(service.evaluate("qa", "blocked", "feedback").blocked());
        verify(policies, never())
                .record(anyString(), anyString(), anyBoolean(), anyInt(), anyList());
    }

    @Test
    void unassignedAccountsDefaultToSharedRulesAndLoggingFollowsWordAction() {
        when(policies.forActor("qa")).thenReturn(List.of());
        assertTrue(service.evaluate("qa", "blocked", "feedback").blocked());
        assertFalse(service.evaluate("qa", "logged", "profile").blocked());
        verify(policies).record(eq("qa"), eq("profile"), eq(false), eq(1), anyList());
    }

    @Test
    void liveRoleSwitchUsesNewPolicyWithoutDiscardingDictionarySnapshot() {
        when(policies.forActor("qa"))
                .thenReturn(List.of(policy("ON")), List.of(policy("OFF")), List.of(policy("ON")));
        assertTrue(service.evaluate("qa", "blocked", "text").blocked());
        assertFalse(service.evaluate("qa", "blocked", "text").blocked());
        assertTrue(service.evaluate("qa", "blocked", "text").blocked());
    }

    @Test
    void previewRejectsOversizedTextAndDisabledRoles() {
        var tooLong =
                assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> service.preview(role, "中".repeat(200001), "qa"));
        assertEquals("sensitiveTextTooLong", tooLong.getReason());
        when(policies.find(role))
                .thenReturn(
                        Optional.of(
                                new RoleContentPolicyRepository.Policy(
                                        role, "QA", "QA", false, "OFF", 1)));
        var disabled =
                assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> service.preview(role, "blocked", "qa"));
        assertEquals("invalidRole", disabled.getReason());
    }

    @Test
    void nullPreviewRequestReturnsClientErrorBeforeDereference() {
        var controller = new RoleContentPolicyController(service);
        var error =
                assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () ->
                                controller.preview(
                                        null,
                                        mock(
                                                org.springframework.security.core.Authentication
                                                        .class)));
        assertEquals(400, error.getStatusCode().value());
    }
}
