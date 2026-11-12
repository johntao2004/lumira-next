package com.lumira.platform.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.MailConfigurationPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.plugins.deliverysettings.MailDeliveryTestService;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MailDeliveryTestServiceTest {
    @Test
    void requiresTheSmtpPageAndTestAction() {
        var settings = mock(com.lumira.platform.common.MailConfigurationPort.class);
        var gateway = mock(SmtpGateway.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("reader", PlatformOperation.SMTP_TEST);

        var service =
                new MailDeliveryTestService(
                        new MailTransportProbeAdapter(settings, gateway), guard, audit);
        assertThrows(ResponseStatusException.class, () -> service.sendLocalTest("reader"));
        verifyNoInteractions(settings, gateway, audit);
        verify(guard, never()).requireOperationGlobal("reader", PlatformOperation.SMTP_UPDATE);
    }

    @Test
    void rejectsRemoteOrAuthenticatedConfigurationWithoutOpeningTransport() {
        var settings = mock(com.lumira.platform.common.MailConfigurationPort.class);
        when(settings.readSettings())
                .thenReturn(
                        new MailConfigurationPort.Settings(
                                true,
                                "smtp.example.test",
                                587,
                                "STARTTLS",
                                false,
                                "",
                                "sender@example.test",
                                "Lumira"));
        var gateway = mock(SmtpGateway.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);

        var service =
                new MailDeliveryTestService(
                        new MailTransportProbeAdapter(settings, gateway), guard, audit);
        var failure =
                assertThrows(ResponseStatusException.class, () -> service.sendLocalTest("admin"));
        assertEquals("smtpLocalTestUnavailable", failure.getReason());
        verifyNoInteractions(gateway, audit);
    }

    @Test
    void sendsOnlyTheFixedSyntheticMessageAndKeepsHostnameCheckingEnabled() throws Exception {
        var settings = mock(com.lumira.platform.common.MailConfigurationPort.class);
        when(settings.readSettings())
                .thenReturn(
                        new MailConfigurationPort.Settings(
                                true,
                                "127.0.0.1",
                                1025,
                                "STARTTLS",
                                false,
                                "",
                                "lumira-qa@example.invalid",
                                "Lumira"));
        var transport = mock(Transport.class);
        var capturedSession = new AtomicReference<Session>();
        var gateway =
                new SmtpGateway(
                        session -> {
                            capturedSession.set(session);
                            return transport;
                        });
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);

        var result =
                new MailDeliveryTestService(
                                new MailTransportProbeAdapter(settings, gateway), guard, audit)
                        .sendLocalTest("admin");

        assertEquals(SmtpGateway.Result.ACCEPTED.name(), result.result());
        verify(transport).connect("127.0.0.1", 1025, null, null);
        assertNotNull(capturedSession.get());
        assertEquals(
                "true", capturedSession.get().getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNotNull(capturedSession.get().getProperties().get("mail.smtp.ssl.socketFactory"));
        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(transport).sendMessage(message.capture(), any(Address[].class));
        assertEquals(
                "smtp-test@example.invalid", message.getValue().getAllRecipients()[0].toString());
        assertEquals(
                "lumira-qa@example.invalid",
                ((InternetAddress) message.getValue().getFrom()[0]).getAddress());
        verify(guard).requireOperationGlobal("admin", PlatformOperation.SMTP_TEST);
        var auditTarget = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(audit).append(eq("admin"), eq("mail.localTest.ACCEPTED"), auditTarget.capture());
        assertNotNull(auditTarget.getValue());
    }

    @Test
    void loopbackTestHostMustResolveAsTheLiteralIpv4LoopbackAddress() {
        assertTrue(SmtpGateway.isLoopbackTestHost("127.0.0.1"));
        assertFalse(SmtpGateway.isLoopbackTestHost("localhost"));
        assertFalse(SmtpGateway.isLoopbackTestHost("smtp.example.test"));
    }
}
