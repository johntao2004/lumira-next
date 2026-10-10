package com.lumira.platform.identity;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local raster and spoken challenges. Secret answers are returned separately for keyed storage
 * only.
 */
@Component
public class LocalCaptchaAssets {
    public static final int IMAGE_WIDTH = 180;
    public static final int IMAGE_HEIGHT = 64;
    public static final int SLIDER_WIDTH = 320;
    public static final int SLIDER_HEIGHT = 160;
    public static final int PIECE_SIZE = 52;
    public static final int SLIDER_TOLERANCE = 8;
    private static final int AUDIO_SAMPLE_RATE = 22_050;
    private static final AudioFormat AUDIO_FORMAT =
            new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    AUDIO_SAMPLE_RATE,
                    16,
                    1,
                    2,
                    AUDIO_SAMPLE_RATE,
                    false);
    private static final List<String> BACKGROUNDS =
            List.of(
                    "captcha/slider-backgrounds/mountain-valley.png",
                    "captcha/slider-backgrounds/neon-city.png",
                    "captcha/slider-backgrounds/botanical-glasshouse.png",
                    "captcha/slider-backgrounds/coastal-harbor.png");
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final java.util.concurrent.ConcurrentMap<String, byte[]> pcmClips =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Boolean audioReady;
    private volatile Boolean sliderReady;

    public record Assets(
            String imageDataUrl,
            String backgroundDataUrl,
            String pieceDataUrl,
            String audioDataUrl,
            int width,
            int height,
            int pieceWidth,
            int pieceHeight,
            int pieceY,
            String answer,
            Integer sliderTargetX) {}

    public boolean audioAvailable() {
        Boolean ready = audioReady;
        if (ready != null) return ready;
        synchronized (this) {
            if (audioReady != null) return audioReady;
            audioReady = inspectAudio();
            return audioReady;
        }
    }

    public boolean typeAvailable(String type) {
        if ("IMAGE_TEXT".equals(type)) return true;
        if (!"SLIDER".equals(type)) return false;
        Boolean ready = sliderReady;
        if (ready != null) return ready;
        synchronized (this) {
            if (sliderReady != null) return sliderReady;
            sliderReady = inspectSliderBackgrounds();
            return sliderReady;
        }
    }

    private boolean inspectAudio() {
        try {
            for (String language : List.of("zh", "en")) {
                for (int digit = 0; digit < 10; digit++) {
                    if (digitPcm(language, digit).length == 0) return false;
                }
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public Assets create(String type, String locale) {
        return switch (type) {
            case "IMAGE_TEXT" -> imageChallenge();
            case "AUDIO" -> audioChallenge(locale);
            case "SLIDER" -> sliderChallenge();
            default -> throw new IllegalArgumentException("invalidCaptchaType");
        };
    }

    private Assets imageChallenge() {
        String answer = generateDigits();
        BufferedImage image =
                new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            quality(graphics);
            graphics.setPaint(
                    new java.awt.GradientPaint(
                            0,
                            0,
                            new Color(245, 250, 255),
                            IMAGE_WIDTH,
                            IMAGE_HEIGHT,
                            new Color(222, 236, 255)));
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
            for (int index = 0; index < 16; index++) {
                graphics.setColor(new Color(51, 91, 144, 75));
                graphics.setStroke(new BasicStroke(1.0f + random.nextFloat() * 1.2f));
                graphics.drawLine(
                        random.nextInt(IMAGE_WIDTH),
                        random.nextInt(IMAGE_HEIGHT),
                        random.nextInt(IMAGE_WIDTH),
                        random.nextInt(IMAGE_HEIGHT));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 32));
            for (int index = 0; index < answer.length(); index++) {
                graphics.setColor(
                        random.nextBoolean() ? new Color(19, 70, 153) : new Color(19, 32, 56));
                int x = 13 + index * 28;
                int y = 44 + random.nextInt(8);
                int angle = random.nextInt(25) - 12;
                graphics.rotate(Math.toRadians(angle), x + 12, y - 10);
                graphics.drawString(answer.substring(index, index + 1), x, y);
                graphics.rotate(Math.toRadians(-angle), x + 12, y - 10);
            }
        } finally {
            graphics.dispose();
        }
        return new Assets(
                png(image), null, null, null, IMAGE_WIDTH, IMAGE_HEIGHT, 0, 0, 0, answer, null);
    }

    private Assets sliderChallenge() {
        BufferedImage original = loadBackground();
        int minX = PIECE_SIZE + 12;
        int maxX = SLIDER_WIDTH - PIECE_SIZE - 12;
        int targetX = minX + random.nextInt(maxX - minX + 1);
        int pieceY = 34 + random.nextInt(SLIDER_HEIGHT - PIECE_SIZE - 40);
        BufferedImage background = copyRgb(original);
        Graphics2D holeGraphics = background.createGraphics();
        try {
            quality(holeGraphics);
            holeGraphics.translate(targetX, pieceY);
            Path2D.Double path = piecePath();
            holeGraphics.setColor(new Color(9, 26, 46, 115));
            holeGraphics.fill(path);
            holeGraphics.setColor(new Color(255, 255, 255, 220));
            holeGraphics.setStroke(new BasicStroke(2.0f));
            holeGraphics.draw(path);
        } finally {
            holeGraphics.dispose();
        }

        BufferedImage piece =
                new BufferedImage(PIECE_SIZE, PIECE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pieceGraphics = piece.createGraphics();
        try {
            quality(pieceGraphics);
            pieceGraphics.setComposite(AlphaComposite.Src);
            pieceGraphics.setClip(piecePath());
            pieceGraphics.drawImage(original, -targetX, -pieceY, null);
            pieceGraphics.setClip(null);
            pieceGraphics.setComposite(AlphaComposite.SrcOver);
            pieceGraphics.setColor(new Color(255, 255, 255, 230));
            pieceGraphics.setStroke(new BasicStroke(2.0f));
            pieceGraphics.draw(piecePath());
        } finally {
            pieceGraphics.dispose();
        }
        return new Assets(
                null,
                png(background),
                png(piece),
                null,
                SLIDER_WIDTH,
                SLIDER_HEIGHT,
                PIECE_SIZE,
                PIECE_SIZE,
                pieceY,
                null,
                targetX);
    }

    private Assets audioChallenge(String locale) {
        String answer = generateDigits();
        String language =
                locale != null && locale.toLowerCase(Locale.ROOT).startsWith("zh") ? "zh" : "en";
        try {
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] silence = new byte[AUDIO_SAMPLE_RATE * 180 / 1000 * AUDIO_FORMAT.getFrameSize()];
            for (int index = 0; index < answer.length(); index++) {
                int digit = answer.charAt(index) - '0';
                pcm.write(digitPcm(language, digit));
                if (index < answer.length() - 1) pcm.write(silence);
            }
            byte[] bytes = pcm.toByteArray();
            long frames = bytes.length / AUDIO_FORMAT.getFrameSize();
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                    AudioInputStream stream = new AudioInputStream(input, AUDIO_FORMAT, frames);
                    ByteArrayOutputStream wave = new ByteArrayOutputStream()) {
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wave);
                return new Assets(
                        null,
                        null,
                        null,
                        "data:audio/wav;base64,"
                                + Base64.getEncoder().encodeToString(wave.toByteArray()),
                        0,
                        0,
                        0,
                        0,
                        0,
                        answer,
                        null);
            }
        } catch (Exception error) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "captchaAccessibleAlternativeUnavailable");
        }
    }

    private byte[] digitPcm(String language, int digit) throws Exception {
        String path = "captcha/audio/" + language + "/" + digit + ".wav";
        byte[] cached = pcmClips.get(path);
        if (cached != null) return cached;
        try (InputStream raw = new BufferedInputStream(resource(path));
                AudioInputStream source = AudioSystem.getAudioInputStream(raw)) {
            AudioInputStream pcm = source;
            if (!AUDIO_FORMAT.matches(source.getFormat()))
                pcm = AudioSystem.getAudioInputStream(AUDIO_FORMAT, source);
            try (AudioInputStream converted = pcm;
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                converted.transferTo(output);
                byte[] decoded = output.toByteArray();
                byte[] existing = pcmClips.putIfAbsent(path, decoded);
                return existing == null ? decoded : existing;
            }
        }
    }

    private BufferedImage loadBackground() {
        String path = BACKGROUNDS.get(random.nextInt(BACKGROUNDS.size()));
        try (InputStream input = new BufferedInputStream(resource(path))) {
            BufferedImage image = ImageIO.read(input);
            if (image == null
                    || image.getWidth() != SLIDER_WIDTH
                    || image.getHeight() != SLIDER_HEIGHT)
                throw new IOException("captcha background unavailable");
            return image;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "captchaUnavailable");
        }
    }

    private boolean inspectSliderBackgrounds() {
        for (String path : BACKGROUNDS) {
            try (InputStream input = new BufferedInputStream(resource(path))) {
                BufferedImage image = ImageIO.read(input);
                if (image == null
                        || image.getWidth() != SLIDER_WIDTH
                        || image.getHeight() != SLIDER_HEIGHT) return false;
            } catch (IOException error) {
                return false;
            }
        }
        return true;
    }

    private static Path2D.Double piecePath() {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(0, 0);
        path.lineTo(18, 0);
        path.curveTo(18, 12, 34, 12, 34, 0);
        path.lineTo(PIECE_SIZE, 0);
        path.lineTo(PIECE_SIZE, 18);
        path.curveTo(PIECE_SIZE - 12, 18, PIECE_SIZE - 12, 34, PIECE_SIZE, 34);
        path.lineTo(PIECE_SIZE, PIECE_SIZE);
        path.lineTo(34, PIECE_SIZE);
        path.curveTo(34, PIECE_SIZE - 12, 18, PIECE_SIZE - 12, 18, PIECE_SIZE);
        path.lineTo(0, PIECE_SIZE);
        path.lineTo(0, 0);
        path.closePath();
        return path;
    }

    private String generateDigits() {
        StringBuilder answer = new StringBuilder(6);
        for (int index = 0; index < 6; index++) answer.append((char) ('0' + random.nextInt(10)));
        return answer.toString();
    }

    private static BufferedImage copyRgb(BufferedImage original) {
        BufferedImage copy =
                new BufferedImage(
                        original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(original, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    private static void quality(Graphics2D graphics) {
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static String png(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "PNG", output))
                throw new IOException("PNG writer unavailable");
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "captchaUnavailable");
        }
    }

    private static InputStream resource(String path) throws IOException {
        return new ClassPathResource(path).getInputStream();
    }
}
