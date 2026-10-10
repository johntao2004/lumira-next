package com.lumira.plugins.diffuseavatar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DiffuseAvatarDeterminismTest {
    private static final UUID FIRST_ACCOUNT =
            UUID.fromString("3d4e03e9-545f-4bf0-a8b5-901151aa2291");

    @Test
    void emitsDeterministicPngForTheSameAccountAndDifferentPngForAnotherAccount() throws Exception {
        var generator = new DiffuseAvatarGenerator();

        byte[] first = generator.generate(FIRST_ACCOUNT);
        byte[] repeated = generator.generate(FIRST_ACCOUNT);
        byte[] another =
                generator.generate(UUID.fromString("84a510f6-899b-4b2c-b232-2edda3b57e03"));

        assertArrayEquals(first, repeated);
        assertFalse(java.util.Arrays.equals(first, another));
        assertTrue(first.length > 8);
        assertArrayEquals(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                java.util.Arrays.copyOf(first, 8));

        var decoded = ImageIO.read(new ByteArrayInputStream(first));
        assertEquals(256, decoded.getWidth());
        assertEquals(256, decoded.getHeight());
        assertTrue(containsSeveralColors(decoded));
    }

    private static boolean containsSeveralColors(java.awt.image.BufferedImage image) {
        int first = image.getRGB(32, 32);
        return first != image.getRGB(128, 128)
                && first != image.getRGB(224, 224)
                && image.getRGB(128, 128) != image.getRGB(224, 224);
    }
}
