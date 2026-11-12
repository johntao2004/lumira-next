package com.lumira.platform.sms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.SmsConfigurationPort;
import com.lumira.plugins.deliverysettings.SmsDeliveryTestService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SmsDeliveryTestServiceTest {
    @Test
    void requiresTheSmsPageAndTestAction() {
        var settings = mock(com.lumira.platform.common.SmsConfigurationPort.class);
        var source = mock(SmsConnectionSource.class);
        var gateway = mock(SmsGateway.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("reader", PlatformOperation.SMS_TEST);

        var service =
                new SmsDeliveryTestService(
                        new SmsTransportProbeAdapter(settings, source, gateway), guard, audit);
        assertThrows(ResponseStatusException.class, () -> service.sendLocalTest("reader"));
        verifyNoInteractions(settings, source, gateway, audit);
        verify(guard, never()).requireOperationGlobal("reader", PlatformOperation.SMS_UPDATE);
    }

    @Test
    void rejectsRemoteReceiversAndDoesNotLoadOrChangeCredentials() {
        var settings = mock(com.lumira.platform.common.SmsConfigurationPort.class);
        var source = mock(SmsConnectionSource.class);
        when(settings.readSettings())
                .thenReturn(
                        new SmsConfigurationPort.Settings(
                                false,
                                "webhook",
                                "https://sms.example.test/send",
                                "Lumira",
                                "Code {code}",
                                10));
        var gateway = mock(SmsGateway.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);

        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                new SmsDeliveryTestService(
                                                new SmsTransportProbeAdapter(
                                                        settings, source, gateway),
                                                guard,
                                                audit)
                                        .sendLocalTest("admin"));
        assertEquals("smsLocalTestUnavailable", failure.getReason());
        verifyNoInteractions(source, gateway, audit);
        verify(settings).readSettings();
    }

    @Test
    void localTestUsesTheStoredCredentialAndFixedSyntheticPayload() {
        var settings = mock(com.lumira.platform.common.SmsConfigurationPort.class);
        when(settings.readSettings())
                .thenReturn(
                        new SmsConfigurationPort.Settings(
                                true,
                                "webhook",
                                "http://127.0.0.1:18080/send",
                                "Lumira",
                                "Code {code}",
                                10));
        byte[] credential = "synthetic-fixture-key".getBytes(StandardCharsets.UTF_8);
        var connection =
                new SmsConnection(
                        new com.lumira.platform.common.SmsConfigurationPort.Settings(
                                true,
                                "webhook",
                                "http://127.0.0.1:18080/send",
                                "Lumira",
                                "Code {code}",
                                10),
                        credential);
        var source = mock(SmsConnectionSource.class);
        when(source.load()).thenReturn(connection);
        var gateway = mock(SmsGateway.class);
        when(gateway.send(any(), any())).thenReturn(SmsGateway.Result.ACCEPTED);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);

        var result =
                new SmsDeliveryTestService(
                                new SmsTransportProbeAdapter(settings, source, gateway),
                                guard,
                                audit)
                        .sendLocalTest("admin");

        assertEquals(SmsGateway.Result.ACCEPTED.name(), result.result());
        var connectionArgument = org.mockito.ArgumentCaptor.forClass(SmsConnection.class);
        var payload = org.mockito.ArgumentCaptor.forClass(OutgoingSms.class);
        verify(gateway).send(connectionArgument.capture(), payload.capture());
        assertSame(connection, connectionArgument.getValue());
        assertEquals("+15555550123", payload.getValue().recipient());
        assertEquals(
                "Lumira local SMS transport test. No account code is included.",
                payload.getValue().text());
        assertArrayEquals(new byte[credential.length], credential);
        verify(guard).requireOperationGlobal("admin", PlatformOperation.SMS_TEST);
        var auditTarget = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(audit).append(eq("admin"), eq("sms.localTest.ACCEPTED"), auditTarget.capture());
        assertNotNull(auditTarget.getValue());
    }

    @Test
    void missingStoredCredentialFailsBeforeGatewayNetworkCall() {
        var settings = mock(com.lumira.platform.common.SmsConfigurationPort.class);
        when(settings.readSettings())
                .thenReturn(
                        new SmsConfigurationPort.Settings(
                                true,
                                "webhook",
                                "http://127.0.0.1:18080/send",
                                "Lumira",
                                "Code {code}",
                                10));
        var source = mock(SmsConnectionSource.class);
        doThrow(
                        new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "smsCredentialRequired"))
                .when(source)
                .load();
        var gateway = mock(SmsGateway.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);

        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                new SmsDeliveryTestService(
                                                new SmsTransportProbeAdapter(
                                                        settings, source, gateway),
                                                guard,
                                                audit)
                                        .sendLocalTest("admin"));

        assertEquals("smsCredentialRequired", failure.getReason());
        verify(source).load();
        verifyNoInteractions(gateway, audit);
    }
}
