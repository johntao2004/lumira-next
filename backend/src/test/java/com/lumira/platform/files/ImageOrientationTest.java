package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageOrientationTest {
    private byte[] tiff(int orientation, ByteOrder order) {
        var b = ByteBuffer.allocate(26).order(order);
        b.putShort((short) (order == ByteOrder.LITTLE_ENDIAN ? 0x4949 : 0x4d4d))
                .putShort((short) 42)
                .putInt(8);
        b.putShort((short) 1)
                .putShort((short) 0x112)
                .putShort((short) 3)
                .putInt(1)
                .putShort((short) orientation)
                .putShort((short) 0)
                .putInt(0);
        return b.array();
    }

    private byte[] jpeg(byte[] metadata, byte[] original) throws IOException {
        var output = new ByteArrayOutputStream();
        var data = new DataOutputStream(output);
        data.writeShort(0xffd8);
        data.writeShort(0xffe1);
        data.writeShort(metadata.length + 8);
        data.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        data.write(metadata);
        data.write(original, 2, original.length - 2);
        return output.toByteArray();
    }

    private byte[] png(byte[] metadata, byte[] original) throws IOException {
        var output = new ByteArrayOutputStream();
        var data = new DataOutputStream(output);
        data.write(original, 0, 33);
        data.writeInt(metadata.length);
        data.writeBytes("eXIf");
        data.write(metadata);
        var crc = new CRC32();
        crc.update(new byte[] {'e', 'X', 'I', 'f'});
        crc.update(metadata);
        data.writeInt((int) crc.getValue());
        data.write(original, 33, original.length - 33);
        return output.toByteArray();
    }

    @Test
    void allEightPixelTransformsIncludingMirrors() {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 3, 2, new int[] {1, 2, 3, 4, 5, 6}, 0, 3);
        int[][] expected = {
            {1, 2, 3, 4, 5, 6},
            {3, 2, 1, 6, 5, 4},
            {6, 5, 4, 3, 2, 1},
            {4, 5, 6, 1, 2, 3},
            {1, 4, 2, 5, 3, 6},
            {4, 1, 5, 2, 6, 3},
            {6, 3, 5, 2, 4, 1},
            {3, 6, 2, 5, 1, 4}
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            var actual = ImageOrientation.apply(image, orientation);
            assertEquals(orientation >= 5 ? 2 : 3, actual.getWidth());
            assertEquals(orientation >= 5 ? 3 : 2, actual.getHeight());
            assertArrayEquals(
                    expected[orientation - 1],
                    actual.getRGB(
                            0,
                            0,
                            actual.getWidth(),
                            actual.getHeight(),
                            null,
                            0,
                            actual.getWidth()));
        }
    }

    @Test
    void readsBothByteOrdersAndRejectsOutOfRangeTags() throws Exception {
        for (var order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            for (int orientation = 1; orientation <= 8; orientation++)
                assertEquals(
                        orientation,
                        ImageOrientation.read(
                                jpeg(
                                        tiff(orientation, order),
                                        new byte[] {
                                            (byte) 255, (byte) 216, (byte) 255, (byte) 217
                                        })));
            assertEquals(
                    1,
                    ImageOrientation.read(
                            jpeg(tiff(9, order), new byte[] {(byte) 255, (byte) 216})));
        }
    }

    @Test
    void malformedOffsetsCountsAndTruncatedSegmentsAreBounded() throws Exception {
        byte[] normal = tiff(6, ByteOrder.LITTLE_ENDIAN);
        for (int offset : new int[] {-1, 0, Integer.MAX_VALUE, 25}) {
            byte[] bad = normal.clone();
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(4, offset);
            assertEquals(1, ImageOrientation.read(jpeg(bad, new byte[] {(byte) 255, (byte) 216})));
        }
        byte[] bad = normal.clone();
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putShort(8, (short) 65535);
        assertEquals(1, ImageOrientation.read(jpeg(bad, new byte[] {(byte) 255, (byte) 216})));
        byte[] encoded = jpeg(normal, new byte[] {(byte) 255, (byte) 216});
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertDoesNotThrow(() -> ImageOrientation.read(truncated));
        }
        var random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            byte[] bytes = new byte[random.nextInt(512)];
            random.nextBytes(bytes);
            assertDoesNotThrow(() -> ImageOrientation.read(bytes));
        }
    }

    @Test
    void normalizerRotatesJpegAndPngAndRemovesTheOrientationMetadata() throws Exception {
        for (String format : new String[] {"JPEG", "PNG"}) {
            var original = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), format, original);
            byte[] input =
                    format.equals("JPEG")
                            ? jpeg(tiff(6, ByteOrder.BIG_ENDIAN), original.toByteArray())
                            : png(tiff(6, ByteOrder.LITTLE_ENDIAN), original.toByteArray());
            var result = ImageNormalizer.normalize(new ByteArrayInputStream(input));
            assertEquals(20, result.width());
            assertEquals(40, result.height());
            assertEquals(1, ImageOrientation.read(result.png()));
            var again = ImageNormalizer.normalize(new ByteArrayInputStream(result.png()));
            assertEquals(20, again.width());
            assertEquals(40, again.height());
        }
    }
}
