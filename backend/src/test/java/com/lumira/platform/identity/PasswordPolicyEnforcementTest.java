package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class PasswordPolicyEnforcementTest {
    @Test
    void sequenceRejectionDoesNotChangePasswordSessionsOrConsumeRecoveryProof() {
        var jdbc = mock(JdbcClient.class);
        var encoder = mock(PasswordEncoder.class);
        var audit = mock(AuditPort.class);
        var mail = mock(MailSender.class);
        var resets = mock(PasswordResetStore.class);
        var security = mock(SecurityPolicyPort.class);
        var captcha = mock(CaptchaProofAccess.class);
        var captchaPort = mock(CaptchaProofPort.class);
        when(captcha.begin(any(), any(), any(), any()))
                .thenReturn(
                        new CaptchaProofAccess.Gate(
                                captchaPort,
                                CaptchaProofPort.Action.PASSWORD_RECOVERY_CONFIRM,
                                new CaptchaProofPort.Requirement(false, "test-policy", null)));
        when(security.currentPolicy())
                .thenReturn(
                        new SecurityPolicyPort.Policy(
                                8, false, false, false, false, 30, 120, 10, 60, 600, 60, 10, 10,
                                5));
        var changes = new PasswordService(jdbc, encoder, audit, security);
        var recovery =
                new PasswordRecoveryService(
                        jdbc,
                        resets,
                        mail,
                        audit,
                        encoder,
                        security,
                        captcha,
                        mock(org.springframework.transaction.PlatformTransactionManager.class));

        var changeError =
                assertThrows(
                        ResponseStatusException.class,
                        () -> changes.change("fixture", "current-password", "Q!r7aBc9"));
        assertEquals("passwordPolicy", changeError.getReason());
        var resetError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                recovery.confirm(
                                        "fixture",
                                        "unused-proof",
                                        "Q!r73219",
                                        null,
                                        new MockHttpServletRequest()));
        assertEquals("passwordPolicy", resetError.getReason());
        verifyNoInteractions(jdbc, encoder, audit, mail, resets);
    }
}
