package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageNormalizerTest {
    private byte[] image(int width, int height, String format) throws IOException {
        var raster =
                new BufferedImage(
                        width,
                        height,
                        format.equals("JPEG")
                                ? BufferedImage.TYPE_INT_RGB
                                : BufferedImage.TYPE_INT_ARGB);
        raster.setRGB(0, 0, 0x80ff0000);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(raster, format, bytes));
        return bytes.toByteArray();
    }

    @Test
    void resizesLargeImagesWithoutUpscalingAndAlwaysEncodesPng() throws Exception {
        var result = ImageNormalizer.normalize(new ByteArrayInputStream(image(1600, 800, "JPEG")));
        assertEquals(512, result.width());
        assertEquals(256, result.height());
        assertEquals(512, ImageIO.read(new ByteArrayInputStream(result.png())).getWidth());
        assertArrayEquals(
                new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10},
                java.util.Arrays.copyOf(result.png(), 8));
        var small = ImageNormalizer.normalize(new ByteArrayInputStream(image(13, 7, "PNG")));
        assertEquals(13, small.width());
        assertEquals(7, small.height());
        assertEquals(128, ImageIO.read(new ByteArrayInputStream(small.png())).getRGB(0, 0) >>> 24);
        var copy = small.png();
        copy[0] = 0;
        assertEquals((byte) 137, small.png()[0]);
    }

    @Test
    void retainsBackgroundResolutionWithoutUndersamplingBeforeResize() throws Exception {
        var result =
                ImageNormalizer.normalize(
                        new ByteArrayInputStream(image(3000, 2000, "JPEG")), 2048);
        assertEquals(2048, result.width());
        assertEquals(1365, result.height());
    }

    @Test
    void rejectsNonImagesAndUnsupportedRasterFormats() throws Exception {
        for (byte[] input :
                new byte[][] {
                    new byte[0],
                    "<svg/>".getBytes(StandardCharsets.UTF_8),
                    new byte[] {(byte) 137, 80, 78, 71}
                })
            assertThrows(
                    ImageNormalizer.InvalidImage.class,
                    () -> ImageNormalizer.normalize(new ByteArrayInputStream(input)));
        var gif = image(2, 2, "GIF");
        assertEquals(
                "unsupportedImage",
                assertThrows(
                                ImageNormalizer.InvalidImage.class,
                                () -> ImageNormalizer.normalize(new ByteArrayInputStream(gif)))
                        .getMessage());
    }

    @Test
    void limitsReadAndDoesNotCloseCallerStreamOrMaskTransportErrors() throws Exception {
        class Source extends InputStream {
            int count;
            boolean closed;

            public int read() {
                count++;
                return 0;
            }

            public void close() {
                closed = true;
            }
        }
        var source = new Source();
        assertEquals(
                "imageTooLarge",
                assertThrows(
                                ImageNormalizer.InvalidImage.class,
                                () -> ImageNormalizer.normalize(source))
                        .getMessage());
        assertEquals(ImageNormalizer.MAXIMUM_BYTES + 1, source.count);
        assertFalse(source.closed);
        var failure = new java.net.SocketTimeoutException("test");
        assertSame(
                failure,
                assertThrows(
                        IOException.class,
                        () ->
                                ImageNormalizer.normalize(
                                        new InputStream() {
                                            public int read() throws IOException {
                                                throw failure;
                                            }
                                        })));
    }

    @Test
    void checksHeaderDimensionsBeforeAllocatingDecodedPixels() throws Exception {
        for (int[] size : new int[][] {{8193, 1}, {5000, 5000}}) {
            byte[] png = image(1, 1, "PNG");
            ByteBuffer.wrap(png).putInt(16, size[0]).putInt(20, size[1]);
            var crc = new CRC32();
            crc.update(png, 12, 17);
            ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
            assertEquals(
                    "imageDimensionsExceeded",
                    assertThrows(
                                    ImageNormalizer.InvalidImage.class,
                                    () -> ImageNormalizer.normalize(new ByteArrayInputStream(png)))
                            .getMessage());
        }
    }

    @Test
    void stripsAncillaryTextAndTrailingPayload() throws Exception {
        byte[] png = image(4, 4, "PNG");
        var data = "Comment\0private source metadata".getBytes(StandardCharsets.ISO_8859_1);
        var chunk = new ByteArrayOutputStream();
        var out = new DataOutputStream(chunk);
        out.writeInt(data.length);
        out.writeBytes("tEXt");
        out.write(data);
        var crc = new CRC32();
        crc.update(chunk.toByteArray(), 4, data.length + 4);
        out.writeInt((int) crc.getValue());
        var combined = new ByteArrayOutputStream();
        combined.write(png, 0, 33);
        combined.write(chunk.toByteArray());
        combined.write(png, 33, png.length - 33);
        combined.write("trailing secret".getBytes(StandardCharsets.UTF_8));
        byte[] cleaned =
                ImageNormalizer.normalize(new ByteArrayInputStream(combined.toByteArray())).png();
        var text = new String(cleaned, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("private source metadata"));
        assertFalse(text.contains("trailing secret"));
        assertEquals(4, ImageIO.read(new ByteArrayInputStream(cleaned)).getHeight());
    }
}
