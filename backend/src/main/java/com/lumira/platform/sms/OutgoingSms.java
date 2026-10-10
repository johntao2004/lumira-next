package com.lumira.platform.sms;

/** A single-recipient verification message. The provider payload is built by the gateway. */
public record OutgoingSms(
        String recipient,
        String text,
        String code,
        Integer lifetimeSeconds,
        String configurationKey) {
    public OutgoingSms(String recipient, String text) {
        this(recipient, text, null, null, null);
    }

    public OutgoingSms {
        if (recipient == null
                || !recipient.matches("\\+[1-9]\\d{6,14}")
                || text == null
                || text.isBlank()
                || text.length() > 1000
                || text.codePoints().anyMatch(Character::isISOControl)
                || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid outgoing SMS");
        }
        if (code != null
                && (!code.matches("[A-Za-z0-9_-]{1,64}")
                        || lifetimeSeconds == null
                        || lifetimeSeconds < 1
                        || lifetimeSeconds > 3600
                        || configurationKey == null
                        || !configurationKey.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("Invalid SMS code metadata");
    }

    @Override
    public String toString() {
        return "OutgoingSms[redacted]";
    }
}
