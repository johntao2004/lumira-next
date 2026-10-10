package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lumira.platform.common.MailSender;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublicAuthContactNormalizerTest {
    private final MailSender mail = mock(MailSender.class);

    @Test
    void normalizesEmailAndPhoneForBothPublicFlows() {
        assertEquals(
                "user@example.com",
                PublicAuthContactNormalizer.normalize(
                        "EMAIL",
                        " User@Example.com ",
                        mail,
                        PublicAuthContactNormalizer.Flow.REGISTRATION));
        verify(mail).validateRecipient("User@Example.com");

        assertEquals(
                "+12125550100",
                PublicAuthContactNormalizer.normalize(
                        "SMS",
                        " +1 (212) 555-0100 ",
                        mail,
                        PublicAuthContactNormalizer.Flow.LOGIN_CODE));
    }

    @Test
    void preservesFlowSpecificErrorReasonsForInvalidEmailAndPhone() {
        doThrow(new IllegalArgumentException("invalid recipient"))
                .when(mail)
                .validateRecipient("invalid-email");

        assertInvalid(
                "EMAIL",
                "invalid-email",
                PublicAuthContactNormalizer.Flow.REGISTRATION,
                "invalidRegistrationContact");
        assertInvalid(
                "SMS",
                "not-a-phone",
                PublicAuthContactNormalizer.Flow.LOGIN_CODE,
                "invalidLoginCodeContact");
    }

    @Test
    void preservesInputLengthLimitsAndFlowSpecificErrorReasons() {
        assertInvalid(
                "EMAIL",
                "x".repeat(321),
                PublicAuthContactNormalizer.Flow.REGISTRATION,
                "invalidRegistrationContact");
        assertInvalid(
                "EMAIL",
                "x".repeat(255) + "@example.com",
                PublicAuthContactNormalizer.Flow.LOGIN_CODE,
                "invalidLoginCodeContact");
    }

    private void assertInvalid(
            String channel,
            String raw,
            PublicAuthContactNormalizer.Flow flow,
            String expectedReason) {
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> PublicAuthContactNormalizer.normalize(channel, raw, mail, flow));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(expectedReason, error.getReason());
    }
}
