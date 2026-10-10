package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;

class LocalCaptchaAssetsTest {
    private final LocalCaptchaAssets assets = new LocalCaptchaAssets();

    @Test
    void imageTextIsASecretFreePngAssetWithSixDigitAnswerKeptSeparately() throws Exception {
        var challenge = assets.create("IMAGE_TEXT", "zh-CN");
        assertTrue(challenge.answer().matches("\\d{6}"));
        assertEquals(LocalCaptchaAssets.IMAGE_WIDTH, challenge.width());
        assertEquals(LocalCaptchaAssets.IMAGE_HEIGHT, challenge.height());
        var imageBytes = decode(challenge.imageDataUrl(), "data:image/png;base64,");
        var image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        assertNotNull(image);
        assertEquals(challenge.width(), image.getWidth());
        assertEquals(challenge.height(), image.getHeight());
        assertTrue(!new String(imageBytes, StandardCharsets.ISO_8859_1).contains("tEXt"));
    }

    @Test
    void sliderReturnsDecodablePngAssetsWithoutExposingTargetX() throws Exception {
        var challenge = assets.create("SLIDER", "en");
        assertTrue(challenge.sliderTargetX() >= LocalCaptchaAssets.PIECE_SIZE + 12);
        var background =
                ImageIO.read(
                        new ByteArrayInputStream(
                                decode(challenge.backgroundDataUrl(), "data:image/png;base64,")));
        var piece =
                ImageIO.read(
                        new ByteArrayInputStream(
                                decode(challenge.pieceDataUrl(), "data:image/png;base64,")));
        assertEquals(LocalCaptchaAssets.SLIDER_WIDTH, background.getWidth());
        assertEquals(LocalCaptchaAssets.SLIDER_HEIGHT, background.getHeight());
        assertEquals(LocalCaptchaAssets.PIECE_SIZE, piece.getWidth());
        assertEquals(LocalCaptchaAssets.PIECE_SIZE, piece.getHeight());
    }

    @Test
    void bundledChineseAndEnglishDigitClipsComposeIntoPlayableWaveAudio() throws Exception {
        assertTrue(assets.audioAvailable());
        var challenge = assets.create("AUDIO", "zh-CN");
        assertTrue(challenge.answer().matches("\\d{6}"));
        byte[] wave = decode(challenge.audioDataUrl(), "data:audio/wav;base64,");
        try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wave))) {
            assertEquals(22_050.0f, stream.getFormat().getSampleRate());
            assertEquals(1, stream.getFormat().getChannels());
            assertEquals(16, stream.getFormat().getSampleSizeInBits());
            assertTrue(stream.readAllBytes().length > 10_000);
        }
        assertTrue(
                assets.create("AUDIO", "en-US")
                        .audioDataUrl()
                        .startsWith("data:audio/wav;base64,"));
    }

    private static byte[] decode(String dataUrl, String prefix) {
        assertNotNull(dataUrl);
        assertTrue(dataUrl.startsWith(prefix));
        return Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
    }
}
