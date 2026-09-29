package com.lumira.platform.files;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Decode accepted raster content and re-encode pixels without source metadata. */
public final class ImageNormalizer {
    public static final int MAXIMUM_BYTES = 5 * 1024 * 1024;
    public static final int MAXIMUM_EDGE = 8192;
    public static final long MAXIMUM_PIXELS = 16_000_000;
    public static final int OUTPUT_EDGE = 512;

    private ImageNormalizer() {}

    public record Normalized(byte[] png, int width, int height) {
        public Normalized { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }

    public static final class InvalidImage extends IOException {
        public InvalidImage(String code) { super(code); }
        private InvalidImage(String code, Throwable cause) { super(code, cause); }
    }

    /** The caller owns source; no filename or client-provided MIME type is trusted. */
    public static Normalized normalize(InputStream source) throws IOException {
        byte[] encoded = source.readNBytes(MAXIMUM_BYTES + 1);
        if (encoded.length > MAXIMUM_BYTES) throw new InvalidImage("imageTooLarge");
        int orientation = ImageOrientation.read(encoded);
        // Memory-only cache avoids ImageIO's process-wide disk cache setting.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidImage("invalidImage");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("PNG") && !format.equalsIgnoreCase("JPEG"))
                    throw new InvalidImage("unsupportedImage");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAXIMUM_EDGE || height > MAXIMUM_EDGE
                        || (long) width * height > MAXIMUM_PIXELS)
                    throw new InvalidImage("imageDimensionsExceeded");
                var parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, (int) Math.ceil(Math.max(width, height) / 1024.0));
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                if (decoded == null) throw new InvalidImage("invalidImage");
                try {
                    double scale = Math.min(1, OUTPUT_EDGE / (double) Math.max(width, height));
                    int targetWidth = Math.max(1, (int) Math.round(width * scale));
                    int targetHeight = Math.max(1, (int) Math.round(height * scale));
                    var clean = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
                    try {
                        var graphics = clean.createGraphics();
                        try {
                            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
                        } finally { graphics.dispose(); }
                        var upright = ImageOrientation.apply(clean, orientation);
                        try {
                            var output = new ByteArrayOutputStream();
                            if (!ImageIO.write(upright, "PNG", output)) throw new IOException("PNG encoder unavailable");
                            return new Normalized(output.toByteArray(), upright.getWidth(), upright.getHeight());
                        } finally { if (upright != clean) upright.flush(); }
                    } finally { clean.flush(); }
                } finally { decoded.flush(); }
            } finally { reader.dispose(); }
        } catch (InvalidImage e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidImage("invalidImage", e);
        }
    }
}
