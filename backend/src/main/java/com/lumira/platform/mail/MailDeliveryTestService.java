package com.lumira.platform.mail;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.MailConfigurationPort;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Sends one fixed synthetic message to IPv4 loopback without loading SMTP credentials. */
@Service
public class MailDeliveryTestService {
    private static final String TEST_RECIPIENT = "smtp-test@example.invalid";
    private static final String TEST_SENDER = "lumira-qa@example.invalid";
    private static final String TEST_SUBJECT = "Lumira local SMTP test";
    private static final String TEST_BODY = "Synthetic local delivery test. No account code is included.";

    public record Result(String result) {}

    private final MailConfigurationPort settings;
    private final SmtpGateway gateway;
    private final AdministrationPort guard;
    private final AuditPort audit;

    public MailDeliveryTestService(MailConfigurationPort settings, SmtpGateway gateway,
            AdministrationPort guard, AuditPort audit) {
        this.settings = settings;
        this.gateway = gateway;
        this.guard = guard;
        this.audit = audit;
    }

    public Result sendLocalTest(String actor) {
        requireWrite(actor);
        MailConfigurationPort.Settings data = settings.readSettings();
        if (!data.enabled() || data.authentication()
                || !SmtpGateway.isLoopbackTestHost(data.host()) || !TEST_SENDER.equalsIgnoreCase(data.fromAddress())) unavailable();

        var transportSettings = new MailConfigurationPort.Settings(true, data.host(), data.port(),
                data.security(), false, "", data.fromAddress(), data.fromName());
        var result = gateway.sendLocalTest(new SmtpConnection(transportSettings, null),
                new OutgoingMail(TEST_RECIPIENT, TEST_SUBJECT, TEST_BODY));
        audit.append(actor, "mail.localTest." + result.name(), UUID.randomUUID());
        return new Result(result.name());
    }

    private void requireWrite(String actor) {
        guard.require(actor, "platform:settings:write");
        guard.requireGlobal(actor, "platform:settings:write");
    }

    private static void unavailable() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "smtpLocalTestUnavailable");
    }
}
