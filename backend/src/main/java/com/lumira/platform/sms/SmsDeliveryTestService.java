package com.lumira.platform.sms;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.SmsConfigurationPort;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Posts one fixed synthetic payload and the configured key to the loopback receiver only. */
@Service
public class SmsDeliveryTestService {
    private static final String TEST_RECIPIENT = "+15555550123";
    private static final String TEST_MESSAGE =
            "Lumira local SMS transport test. No account code is included.";

    public record Result(String result) {}

    private final SmsConfigurationPort settings;
    private final SmsConnectionSource source;
    private final SmsGateway gateway;
    private final AdministrationPort guard;
    private final AuditPort audit;

    public SmsDeliveryTestService(
            SmsConfigurationPort settings,
            SmsConnectionSource source,
            SmsGateway gateway,
            AdministrationPort guard,
            AuditPort audit) {
        this.settings = settings;
        this.source = source;
        this.gateway = gateway;
        this.guard = guard;
        this.audit = audit;
    }

    public Result sendLocalTest(String actor) {
        requireWrite(actor);
        var data = settings.readSettings();
        if (!data.enabled()
                || !"webhook".equals(data.provider())
                || !SmsGateway.isLoopbackTestEndpoint(data.endpoint())) unavailable();

        try (var connection = source.load()) {
            if (!"webhook".equals(connection.settings().provider())
                    || !SmsGateway.isLoopbackTestEndpoint(connection.settings().endpoint()))
                unavailable();
            var result = gateway.send(connection, new OutgoingSms(TEST_RECIPIENT, TEST_MESSAGE));
            audit.append(actor, "sms.localTest." + result.name(), UUID.randomUUID());
            return new Result(result.name());
        }
    }

    private void requireWrite(String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.SMS_TEST);
    }

    private static void unavailable() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "smsLocalTestUnavailable");
    }
}
