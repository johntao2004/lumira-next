package com.lumira.platform.sms;

import com.lumira.platform.common.SmsConfigurationPort;
import java.util.Objects;

public final class SmsConnection implements AutoCloseable {
    private final SmsConfigurationPort.Configuration configuration;
    private boolean closed;
    public SmsConnection(SmsConfigurationPort.Configuration configuration) { this.configuration = Objects.requireNonNull(configuration, "sms configuration"); }
    public SmsConnection(SmsConfigurationPort.Settings settings, byte[] secret) { this(new SmsConfigurationPort.Configuration(settings, secret)); }
    public SmsConfigurationPort.Settings settings() { if (closed) throw new IllegalStateException("SMS connection closed"); return configuration.settings(); }
    public byte[] secret() { return closed || configuration.secret() == null ? null : configuration.secret().clone(); }
    @Override public void close() { configuration.clear(); closed = true; }
    @Override public String toString() { return "SmsConnection[redacted]"; }
}
