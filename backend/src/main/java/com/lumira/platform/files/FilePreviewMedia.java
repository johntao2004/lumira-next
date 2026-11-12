package com.lumira.platform.files;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Decode local files into bounded pixels; never embed uploaded HTML, SVG, or PDF scripts. */
final class FilePreviewMedia {
    private static final int OUTPUT_EDGE = 2048;

    private FilePreviewMedia() {}

    static FilePreview image(byte[] bytes, String extension) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals(extension.equals("jpg") ? "jpeg" : extension)) throw invalid();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1
                        || height < 1
                        || width > ImageNormalizer.MAXIMUM_EDGE
                        || height > ImageNormalizer.MAXIMUM_EDGE
                        || (long) width * height > ImageNormalizer.MAXIMUM_PIXELS) {
                    throw new ResponseStatusException(
                            HttpStatus.PAYLOAD_TOO_LARGE, "filePreviewTooLarge");
                }
                var parameters = reader.getDefaultReadParam();
                int sampling =
                        Math.max(
                                1, (int) Math.ceil(Math.max(width, height) / (double) OUTPUT_EDGE));
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage decoded = reader.read(0, parameters);
                if (decoded == null) throw invalid();
                try {
                    var upright = ImageOrientation.apply(decoded, ImageOrientation.read(bytes));
                    try {
                        return new FilePreview("", false, encode(upright), 1, 1);
                    } finally {
                        if (upright != decoded) upright.flush();
                    }
                } finally {
                    decoded.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static String encode(BufferedImage source) throws IOException {
        var clean =
                new BufferedImage(
                        source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        try {
            var graphics = clean.createGraphics();
            try {
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(clean, "PNG", output))
                throw new IOException("PNG encoder unavailable");
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(output.toByteArray());
        } finally {
            clean.flush();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "filePreviewInvalid");
    }
}
