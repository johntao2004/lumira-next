package com.lumira.platform.mail;

import com.lumira.platform.common.MailConfigurationPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Sends one fixed synthetic message to IPv4 loopback without loading SMTP credentials. */
@Service
public class MailTransportProbeAdapter
        implements com.lumira.platform.common.MailTransportProbePort {
    private static final String TEST_RECIPIENT = "smtp-test@example.invalid";
    private static final String TEST_SENDER = "lumira-qa@example.invalid";
    private static final String TEST_SUBJECT = "Lumira local SMTP test";
    private static final String TEST_BODY =
            "Synthetic local delivery test. No account code is included.";

    private final MailConfigurationPort settings;
    private final SmtpGateway gateway;

    public MailTransportProbeAdapter(MailConfigurationPort settings, SmtpGateway gateway) {
        this.settings = settings;
        this.gateway = gateway;
    }

    @Override
    public String sendLocalTest() {
        MailConfigurationPort.Settings data = settings.readSettings();
        if (!data.enabled()
                || data.authentication()
                || !SmtpGateway.isLoopbackTestHost(data.host())
                || !TEST_SENDER.equalsIgnoreCase(data.fromAddress())) unavailable();

        var transportSettings =
                new MailConfigurationPort.Settings(
                        true,
                        data.host(),
                        data.port(),
                        data.security(),
                        false,
                        "",
                        data.fromAddress(),
                        data.fromName());
        var result =
                gateway.sendLocalTest(
                        new SmtpConnection(transportSettings, null),
                        new OutgoingMail(TEST_RECIPIENT, TEST_SUBJECT, TEST_BODY));
        return result.name();
    }

    private static void unavailable() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "smtpLocalTestUnavailable");
    }
}
