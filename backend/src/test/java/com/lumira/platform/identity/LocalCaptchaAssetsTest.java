package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

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
    void allBundledDigitClipsAreDecodableSpeechWithHeadroomAndReasonableDuration()
            throws Exception {
        assertTrue(assets.audioAvailable());
        for (String language : List.of("zh", "en")) {
            for (int digit = 0; digit < 10; digit++) {
                String path = "captcha/audio/" + language + "/" + digit + ".wav";
                try (var stream =
                        AudioSystem.getAudioInputStream(
                                new BufferedInputStream(
                                        new ClassPathResource(path).getInputStream()))) {
                    var format = stream.getFormat();
                    assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding(), path);
                    assertEquals(22_050.0f, format.getSampleRate(), path);
                    assertEquals(16, format.getSampleSizeInBits(), path);
                    assertEquals(1, format.getChannels(), path);
                    assertEquals(2, format.getFrameSize(), path);
                    assertTrue(!format.isBigEndian(), path);

                    byte[] pcm = stream.readAllBytes();
                    assertTrue(pcm.length > 0 && pcm.length % format.getFrameSize() == 0, path);
                    int frameCount = pcm.length / format.getFrameSize();
                    double durationSeconds = frameCount / format.getSampleRate();
                    assertTrue(durationSeconds >= 0.25 && durationSeconds <= 1.5, path);
                    int peak = 0;
                    int activeSamples = 0;
                    for (int offset = 0; offset < pcm.length; offset += format.getFrameSize()) {
                        int sample = (short) ((pcm[offset + 1] << 8) | (pcm[offset] & 0xff));
                        int magnitude = Math.abs(sample);
                        peak = Math.max(peak, magnitude);
                        if (magnitude > 100) activeSamples++;
                        assertTrue(sample > Short.MIN_VALUE && sample < Short.MAX_VALUE, path);
                    }
                    assertTrue(peak > 500, path);
                    assertTrue(activeSamples > frameCount / 20, path);
                }
            }
        }
    }

    @Test
    void audioCompositionUsesLocaleFallbackAndIncludesReadableBoundaryPausesWithoutMetadata()
            throws Exception {
        for (var selection :
                List.of(
                        new AudioSelection("zh-CN", "zh"),
                        new AudioSelection("en-US", "en"),
                        new AudioSelection("ja-JP", "en"))) {
            var challenge = assets.create("AUDIO", selection.locale());
            assertTrue(challenge.answer().matches("\\d{6}"));
            byte[] wave = decode(challenge.audioDataUrl(), "data:audio/wav;base64,");
            List<String> chunkIds = waveChunkIds(wave);
            assertEquals(List.of("fmt ", "data"), chunkIds);
            byte[] actualPcm;
            try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wave))) {
                var format = stream.getFormat();
                assertEquals(22_050.0f, format.getSampleRate());
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
                assertEquals(1, format.getChannels());
                assertEquals(16, format.getSampleSizeInBits());
                assertEquals(2, format.getFrameSize());
                assertTrue(!format.isBigEndian());
                actualPcm = stream.readAllBytes();
                int actualFrames = actualPcm.length / format.getFrameSize();
                int expectedFrames = silenceFrames(150) + silenceFrames(250);
                expectedFrames += 5 * silenceFrames(350);
                for (char character : challenge.answer().toCharArray()) {
                    expectedFrames += bundledDigitFrames(selection.language(), character - '0');
                }
                assertEquals(expectedFrames, actualFrames);
            }
            assertArrayEquals(
                    expectedComposition(challenge.answer(), selection.language()), actualPcm);
        }
    }

    private static byte[] expectedComposition(String answer, String language) throws Exception {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(new byte[silenceFrames(150) * 2]);
        for (int index = 0; index < answer.length(); index++) {
            expected.write(readBundledPcm(language, answer.charAt(index) - '0'));
            if (index < answer.length() - 1) expected.write(new byte[silenceFrames(350) * 2]);
        }
        expected.write(new byte[silenceFrames(250) * 2]);
        return expected.toByteArray();
    }

    private static int bundledDigitFrames(String language, int digit) throws Exception {
        return readBundledPcm(language, digit).length / 2;
    }

    private static byte[] readBundledPcm(String language, int digit) throws Exception {
        String path = "captcha/audio/" + language + "/" + digit + ".wav";
        try (var stream =
                AudioSystem.getAudioInputStream(
                        new BufferedInputStream(new ClassPathResource(path).getInputStream()))) {
            return stream.readAllBytes();
        }
    }

    private static int silenceFrames(int durationMillis) {
        return (int) Math.round(22_050 * durationMillis / 1000.0);
    }

    private static List<String> waveChunkIds(byte[] wave) {
        assertTrue(wave.length >= 12);
        assertEquals("RIFF", new String(wave, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wave, 8, 4, StandardCharsets.US_ASCII));
        List<String> chunkIds = new ArrayList<>();
        int offset = 12;
        while (offset + 8 <= wave.length) {
            chunkIds.add(new String(wave, offset, 4, StandardCharsets.US_ASCII));
            long size = Integer.toUnsignedLong(readLittleEndianInt(wave, offset + 4));
            long nextOffset = offset + 8L + size + (size & 1);
            assertTrue(nextOffset <= wave.length);
            offset = (int) nextOffset;
        }
        assertEquals(wave.length, offset);
        return chunkIds;
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return bytes[offset] & 0xff
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16
                | (bytes[offset + 3] & 0xff) << 24;
    }

    private record AudioSelection(String locale, String language) {}

    private static byte[] decode(String dataUrl, String prefix) {
        assertNotNull(dataUrl);
        assertTrue(dataUrl.startsWith(prefix));
        return Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
    }
}
