package com.lumira.platform.sms;

/** A single-recipient verification message. The provider payload is built by the gateway. */
public record OutgoingSms(String recipient, String text) {
    public OutgoingSms {
        if (recipient == null || !recipient.matches("\\+[1-9]\\d{6,14}")
                || text == null || text.isBlank() || text.length() > 1000
                || text.codePoints().anyMatch(Character::isISOControl) || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid outgoing SMS");
        }
    }
    @Override public String toString() { return "OutgoingSms[redacted]"; }
}
