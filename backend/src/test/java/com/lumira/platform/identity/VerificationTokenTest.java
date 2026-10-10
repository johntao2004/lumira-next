package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VerificationTokenTest {
    @Test
    void tokensAreRandomCanonicalAndRedacted() {
        var one = VerificationToken.issue();
        var two = VerificationToken.issue();
        assertNotEquals(one, two);
        assertEquals(43, one.value().length());
        assertEquals(64, one.digest().length());
        assertEquals(one.digest(), VerificationToken.digest(one.value()));
        assertEquals("VerificationToken[redacted]", one.toString());
        for (String bad : new String[] {"", one.value() + "=", "x".repeat(44), "!".repeat(43)})
            assertEquals("", VerificationToken.digest(bad));
        assertEquals("", VerificationToken.digest(null));
    }
}
