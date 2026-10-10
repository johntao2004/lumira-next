package com.lumira.platform.sms;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OutgoingSmsTest {
    @Test
    void acceptsE164AndNeverIncludesContentInToString() {
        var sms = new OutgoingSms("+8613800138000", "Code 123");
        assertEquals("+8613800138000", sms.recipient());
        assertFalse(sms.toString().contains("Code 123"));
    }

    @Test
    void rejectsInvalidRecipientsAndControlCharacters() {
        for (String phone :
                new String[] {"13800138000", "+0123456789", "+86123", "+8613800138000\n"})
            assertThrows(IllegalArgumentException.class, () -> new OutgoingSms(phone, "Code"));
        assertThrows(
                IllegalArgumentException.class, () -> new OutgoingSms("+8613800138000", "Code\n"));
    }
}
