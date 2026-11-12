package com.lumira.platform.sms;

import com.lumira.platform.common.SmsConfigurationPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Posts one fixed synthetic payload and the configured key to the loopback receiver only. */
@Service
public class SmsTransportProbeAdapter implements com.lumira.platform.common.SmsTransportProbePort {
    private static final String TEST_RECIPIENT = "+15555550123";
    private static final String TEST_MESSAGE =
            "Lumira local SMS transport test. No account code is included.";

    private final SmsConfigurationPort settings;
    private final SmsConnectionSource source;
    private final SmsGateway gateway;

    public SmsTransportProbeAdapter(
            SmsConfigurationPort settings, SmsConnectionSource source, SmsGateway gateway) {
        this.settings = settings;
        this.source = source;
        this.gateway = gateway;
    }

    @Override
    public String sendLocalTest() {
        var data = settings.readSettings();
        if (!data.enabled()
                || !"webhook".equals(data.provider())
                || !SmsGateway.isLoopbackTestEndpoint(data.endpoint())) unavailable();

        try (var connection = source.load()) {
            if (!"webhook".equals(connection.settings().provider())
                    || !SmsGateway.isLoopbackTestEndpoint(connection.settings().endpoint()))
                unavailable();
            var result = gateway.send(connection, new OutgoingSms(TEST_RECIPIENT, TEST_MESSAGE));
            return result.name();
        }
    }

    private static void unavailable() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "smsLocalTestUnavailable");
    }
}
