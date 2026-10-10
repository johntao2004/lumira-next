package com.lumira.platform.mail;

import com.lumira.platform.common.MailConfigurationPort;
import org.springframework.stereotype.Service;

/** Internal communication adapter; configuration persistence stays behind the port. */
@Service
public class SmtpConnectionSource {
    private final MailConfigurationPort configurations;

    public SmtpConnectionSource(MailConfigurationPort configurations) {
        this.configurations = configurations;
    }

    public boolean ready() {
        return configurations.ready();
    }

    public SmtpConnection load() {
        var configuration = configurations.load();
        try {
            return new SmtpConnection(configuration);
        } catch (RuntimeException | Error failure) {
            configuration.clear();
            throw failure;
        }
    }
}
