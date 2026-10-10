package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SecretKeysTest {
    private final String encoded = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void absentKeysDoNotGenerateAnEphemeralReplacement() {
        var keys = new SecretKeys("", "primary");
        assertFalse(keys.configured());
        var error = assertThrows(ResponseStatusException.class, keys::require);
        assertEquals(503, error.getStatusCode().value());
        assertEquals("secretKeyUnavailable", error.getReason());
    }

    @Test
    void configuredKeySurvivesTemporaryDecodeBufferClearing() {
        var keys = new SecretKeys("primary=" + encoded, "primary");
        assertTrue(keys.configured());
        byte[] plain = {1, 2, 3};
        String ciphertext = keys.require().encrypt("smtp:1:password", plain);
        assertArrayEquals(
                plain,
                new SecretKeys("primary=" + encoded, "primary")
                        .require()
                        .decrypt("smtp:1:password", ciphertext));
    }

    @Test
    void rejectsMalformedDuplicateShortAndUnknownActiveKeysWithoutEchoingInput() {
        for (String raw :
                new String[] {
                    "bad secret value",
                    "primary=secret",
                    "primary=" + encoded + ",primary=" + encoded,
                    "primary=" + encoded + ",",
                    "primary=" + encoded.replace("=", "")
                }) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class, () -> new SecretKeys(raw, "primary"));
            assertEquals("Invalid secret key configuration", error.getMessage());
            assertNull(error.getCause());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new SecretKeys("primary=" + encoded, "missing"));
    }
}
