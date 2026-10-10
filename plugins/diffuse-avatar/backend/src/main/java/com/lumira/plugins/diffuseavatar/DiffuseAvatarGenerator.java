package com.lumira.plugins.diffuseavatar;

import com.lumira.platform.common.GeneratedAvatarPort;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Creates a deterministic diffuse color field without retaining account data or image state. */
public final class DiffuseAvatarGenerator implements GeneratedAvatarPort {
    private static final int SIZE = 256;
    private static final int MAX_PNG_BYTES = 512 * 1024;
    private static final int BLOB_COUNT = 6;
    private static final long COLOR_SEED_SALT = 0x44c2a87628f49b1dL;
    private static final long SHAPE_SEED_SALT = 0x297baf43e81c6305L;

    @Override
    public byte[] generate(UUID accountId) {
        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        long seed = seedFrom(accountId);
        BufferedImage image = render(seed);
        try (var bytes = new BoundedByteArrayOutputStream(MAX_PNG_BYTES);
                var output = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
            try {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
                output.flush();
                return bytes.toByteArray();
            } finally {
                writer.dispose();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode generated avatar", e);
        }
    }

    /**
     * Returns the background followed by six blob colors as [OKLCH lightness, chroma, hue]. This
     * package-private pure helper supports deterministic palette verification.
     */
    static double[][] generateOklchPalette(long seed) {
        var random = new SeededRandom(seed ^ COLOR_SEED_SALT);
        double baseHue = random.nextDouble(0, 360);
        double direction = (random.nextLong() & 1) == 0 ? 1 : -1;
        double secondHue = normalizeHue(baseHue + direction * random.nextDouble(65, 145));
        double backgroundHue = normalizeHue(baseHue + random.nextDouble(-180, 180));

        var palette = new double[BLOB_COUNT + 1][3];
        palette[0] =
                new double[] {
                    random.nextDouble(0.94, 0.985), random.nextDouble(0.01, 0.026), backgroundHue
                };
        for (int i = 0; i < BLOB_COUNT; i++) {
            double hueAnchor = i % 2 == 0 ? baseHue : secondHue;
            double hue = normalizeHue(hueAnchor + random.nextDouble(-26, 26));
            double lightness = random.nextDouble(0.68, 0.83);
            double chroma = random.nextDouble(0.14, 0.25);
            palette[i + 1] = new double[] {lightness, chroma, hue};
        }
        return palette;
    }

    /**
     * Converts OKLCH to in-gamut linear sRGB, preserving lightness and hue during chroma mapping.
     * The OKLab matrices follow Björn Ottosson's public-domain/MIT formulas; gamut mapping follows
     * W3C CSS Color 4 chroma-reduction guidance.
     *
     * @see <a href="https://bottosson.github.io/posts/oklab/">Oklab conversion formulas</a>
     * @see <a href="https://www.w3.org/TR/css-color-4/#chroma-reduction">CSS Color 4 chroma
     *     reduction</a>
     */
    static double[] oklchToLinearSrgb(double lightness, double chroma, double hueDegrees) {
        double mappedChroma = reduceChromaToSrgbGamut(lightness, chroma, hueDegrees);
        double[] linearRgb = oklchToLinearSrgbUnchecked(lightness, mappedChroma, hueDegrees);
        return clampToSrgbGamut(linearRgb);
    }

    static boolean isInSrgbGamut(double[] linearRgb) {
        return linearRgb.length == 3
                && inRange(linearRgb[0])
                && inRange(linearRgb[1])
                && inRange(linearRgb[2]);
    }

    private static BufferedImage render(long seed) {
        double[][] palette = generateOklchPalette(seed);
        double[][] linearColors = new double[palette.length][];
        for (int i = 0; i < palette.length; i++) {
            linearColors[i] = oklchToLinearSrgb(palette[i][0], palette[i][1], palette[i][2]);
        }

        var random = new SeededRandom(seed ^ SHAPE_SEED_SALT);
        var blobs = new Blob[BLOB_COUNT];
        double phase = random.nextDouble(0, Math.PI * 2);
        for (int i = 0; i < BLOB_COUNT; i++) {
            double angle = (Math.PI * 2 * i / BLOB_COUNT) + phase + random.nextDouble(-0.38, 0.38);
            double radius = random.nextDouble(0.14, 0.33);
            double centerX = 0.5 + Math.cos(angle) * radius + random.nextDouble(-0.08, 0.08);
            double centerY = 0.5 + Math.sin(angle) * radius + random.nextDouble(-0.08, 0.08);
            blobs[i] =
                    new Blob(
                            centerX,
                            centerY,
                            random.nextDouble(0.22, 0.42),
                            random.nextDouble(0.64, 1.18),
                            linearColors[i + 1]);
        }

        var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        double[] background = linearColors[0];
        for (int y = 0; y < SIZE; y++) {
            double normalizedY = (y + 0.5) / SIZE;
            for (int x = 0; x < SIZE; x++) {
                double normalizedX = (x + 0.5) / SIZE;
                double red = background[0] * 0.34;
                double green = background[1] * 0.34;
                double blue = background[2] * 0.34;
                double totalWeight = 0.34;
                for (Blob blob : blobs) {
                    double dx = (normalizedX - blob.x) / (blob.radius * 1.18);
                    double dy = (normalizedY - blob.y) / blob.radius;
                    double distanceSquared = dx * dx + dy * dy;
                    double weight = blob.strength * Math.exp(-2.15 * distanceSquared);
                    red += blob.color[0] * weight;
                    green += blob.color[1] * weight;
                    blue += blob.color[2] * weight;
                    totalWeight += weight;
                }
                int r = toSrgb(red / totalWeight);
                int g = toSrgb(green / totalWeight);
                int b = toSrgb(blue / totalWeight);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    static double reduceChromaToSrgbGamut(double lightness, double chroma, double hueDegrees) {
        double hue = normalizeHue(hueDegrees);
        double[] initial = oklchToLinearSrgbUnchecked(lightness, chroma, hue);
        if (isInSrgbGamut(initial)) return chroma;

        double low = 0;
        double high = chroma;
        for (int i = 0; i < 24; i++) {
            double candidate = (low + high) / 2;
            if (isInSrgbGamut(oklchToLinearSrgbUnchecked(lightness, candidate, hue))) {
                low = candidate;
            } else {
                high = candidate;
            }
        }
        return low;
    }

    static double[] oklchToLinearSrgbUnchecked(double lightness, double chroma, double hueDegrees) {
        double radians = Math.toRadians(hueDegrees);
        double a = chroma * Math.cos(radians);
        double b = chroma * Math.sin(radians);

        double lPrime = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double mPrime = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double sPrime = lightness - 0.0894841775 * a - 1.2914855480 * b;
        double l = lPrime * lPrime * lPrime;
        double m = mPrime * mPrime * mPrime;
        double s = sPrime * sPrime * sPrime;

        return new double[] {
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        };
    }

    private static double[] clampToSrgbGamut(double[] linearRgb) {
        return new double[] {clamp(linearRgb[0]), clamp(linearRgb[1]), clamp(linearRgb[2])};
    }

    private static boolean inRange(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double normalizeHue(double hue) {
        double normalized = hue % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private static int toSrgb(double linear) {
        double value = clamp(linear);
        double srgb =
                value <= 0.0031308 ? 12.92 * value : 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
        return (int) Math.round(srgb * 255);
    }

    private static long seedFrom(UUID accountId) {
        return mix64(accountId.getMostSignificantBits())
                ^ Long.rotateLeft(mix64(accountId.getLeastSignificantBits()), 23);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record Blob(double x, double y, double radius, double strength, double[] color) {}

    private static final class SeededRandom {
        private long state;

        private SeededRandom(long seed) {
            state = seed;
        }

        private long nextLong() {
            state += 0x9e3779b97f4a7c15L;
            return mix64(state);
        }

        private double nextDouble(double min, double max) {
            long fraction = nextLong() >>> 11;
            return min + (max - min) * (fraction * 0x1.0p-53);
        }
    }

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maxBytes;

        private BoundedByteArrayOutputStream(int maxBytes) {
            super(32 * 1024);
            this.maxBytes = maxBytes;
        }

        @Override
        public synchronized void write(int value) {
            ensureWithinLimit(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            ensureWithinLimit(length);
            super.write(values, offset, length);
        }

        private void ensureWithinLimit(int additionalBytes) {
            if (additionalBytes < 0 || count > maxBytes - additionalBytes)
                throw new IllegalStateException("Generated avatar exceeds its PNG size limit");
        }
    }
}
