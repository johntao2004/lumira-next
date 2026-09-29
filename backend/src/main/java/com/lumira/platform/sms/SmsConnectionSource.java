package com.lumira.platform.sms;

import com.lumira.platform.common.SmsConfigurationPort;
import org.springframework.stereotype.Service;

/** Internal communication adapter; configuration persistence stays behind the port. */
@Service
public class SmsConnectionSource {
    private final SmsConfigurationPort configurations;
    public SmsConnectionSource(SmsConfigurationPort configurations) { this.configurations = configurations; }
    public SmsConnection load() {
        var configuration = configurations.load();
        try {
            return new SmsConnection(configuration);
        } catch (RuntimeException | Error failure) {
            configuration.clear();
            throw failure;
        }
    }
}
