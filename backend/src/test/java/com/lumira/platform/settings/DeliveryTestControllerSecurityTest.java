package com.lumira.platform.settings;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.mail.MailDeliveryTestService;
import com.lumira.platform.mail.SmtpTestController;
import com.lumira.platform.mail.SmtpGateway;
import com.lumira.platform.sms.SmsConnectionSource;
import com.lumira.platform.sms.SmsDeliveryTestService;
import com.lumira.platform.sms.SmsGateway;
import com.lumira.platform.sms.SmsTestController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class DeliveryTestControllerSecurityTest {
    @Test void smtpTestIsPostOnlyAndRejectsANonWriterBeforeReadingSettings() throws Exception {
        var settings = mock(SmtpSettingsService.class);
        var gateway = mock(SmtpGateway.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
            .when(guard).require("reader", "platform:settings:write");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SmtpTestController(
            new MailDeliveryTestService(settings, gateway, guard, audit))).build();

        mvc.perform(get("/api/platform/settings/smtp/test"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/platform/settings/smtp/test").principal(authentication("reader")))
            .andExpect(status().isForbidden());

        verify(guard).require("reader", "platform:settings:write");
        verifyNoInteractions(settings, gateway, audit);
    }

    @Test void smsTestIsPostOnlyAndRejectsANonWriterBeforeLoadingCredentials() throws Exception {
        var settings = mock(SmsSettingsService.class);
        var source = mock(SmsConnectionSource.class);
        var gateway = mock(SmsGateway.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
            .when(guard).require("reader", "platform:settings:write");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SmsTestController(
            new SmsDeliveryTestService(settings, source, gateway, guard, audit))).build();

        mvc.perform(get("/api/platform/settings/sms/test"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/platform/settings/sms/test").principal(authentication("reader")))
            .andExpect(status().isForbidden());

        verify(guard).require("reader", "platform:settings:write");
        verifyNoInteractions(settings, source, gateway, audit);
    }

    private static UsernamePasswordAuthenticationToken authentication(String name) {
        return new UsernamePasswordAuthenticationToken(name, "", List.of());
    }
}
