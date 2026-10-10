package com.lumira.platform.mail;

import com.lumira.platform.common.MailConfigurationPort;
import java.util.Objects;

/** Owns decrypted bytes for one attempt; never put this object into a cache or response. */
public final class SmtpConnection implements AutoCloseable {
    private final MailConfigurationPort.Configuration configuration;
    private boolean closed;

    public SmtpConnection(MailConfigurationPort.Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "mail configuration");
    }

    public SmtpConnection(MailConfigurationPort.Settings settings, byte[] password) {
        this(new MailConfigurationPort.Configuration(settings, password));
    }

    public MailConfigurationPort.Settings settings() {
        if (closed) throw new IllegalStateException("Connection closed");
        return configuration.settings();
    }

    byte[] password() {
        if (closed) throw new IllegalStateException("Connection closed");
        return configuration.password();
    }

    @Override
    public void close() {
        configuration.clear();
        closed = true;
    }

    @Override
    public String toString() {
        return "SmtpConnection[redacted]";
    }
}
