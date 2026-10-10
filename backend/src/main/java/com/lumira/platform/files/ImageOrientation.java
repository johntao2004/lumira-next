package com.lumira.platform.files;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded EXIF IFD0 orientation lookup; malformed/absent metadata means upright. */
final class ImageOrientation {
    private ImageOrientation() {}

    static int read(byte[] bytes) {
        if (bytes.length < 4) return 1;
        if ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216) {
            int cursor = 2;
            while (cursor + 4 <= bytes.length) {
                if ((bytes[cursor++] & 255) != 255) return 1;
                while (cursor < bytes.length && (bytes[cursor] & 255) == 255) cursor++;
                if (cursor >= bytes.length) return 1;
                int marker = bytes[cursor++] & 255;
                if (marker == 218 || marker == 217) return 1; // Never scan entropy-coded data.
                if (marker == 1 || (marker >= 208 && marker <= 215)) continue;
                if (cursor + 2 > bytes.length) return 1;
                int length = ((bytes[cursor] & 255) << 8) | (bytes[cursor + 1] & 255);
                if (length < 2 || length > bytes.length - cursor) return 1;
                if (marker == 225
                        && length >= 8
                        && bytes[cursor + 2] == 'E'
                        && bytes[cursor + 3] == 'x'
                        && bytes[cursor + 4] == 'i'
                        && bytes[cursor + 5] == 'f'
                        && bytes[cursor + 6] == 0
                        && bytes[cursor + 7] == 0) return tiff(bytes, cursor + 8, length - 8);
                cursor += length;
            }
        } else if (bytes.length >= 8 && ByteBuffer.wrap(bytes).getLong() == 0x89504e470d0a1a0aL) {
            int cursor = 8;
            while (bytes.length - cursor >= 12) {
                int length = ByteBuffer.wrap(bytes, cursor, 4).getInt();
                if (length < 0 || length > bytes.length - cursor - 12) return 1;
                int type = ByteBuffer.wrap(bytes, cursor + 4, 4).getInt();
                if (type == 0x65584966) return tiff(bytes, cursor + 8, length); // eXIf
                if (type == 0x49454e44) return 1;
                cursor += length + 12;
            }
        }
        return 1;
    }

    private static int tiff(byte[] bytes, int start, int length) {
        if (length < 8) return 1;
        var buffer = ByteBuffer.wrap(bytes, start, length).slice();
        short order = buffer.getShort(0);
        if (order == (short) 0x4949) buffer.order(ByteOrder.LITTLE_ENDIAN);
        else if (order != (short) 0x4d4d) return 1;
        if (Short.toUnsignedInt(buffer.getShort(2)) != 42) return 1;
        long offset = Integer.toUnsignedLong(buffer.getInt(4));
        if (offset < 8 || offset > length - 2) return 1;
        int count = Short.toUnsignedInt(buffer.getShort((int) offset));
        int first = (int) offset + 2;
        if (count > (length - first) / 12) return 1;
        for (int i = 0; i < count; i++) {
            int entry = first + i * 12;
            if (Short.toUnsignedInt(buffer.getShort(entry)) != 0x112) continue;
            if (Short.toUnsignedInt(buffer.getShort(entry + 2)) != 3
                    || buffer.getInt(entry + 4) != 1) return 1;
            int orientation = Short.toUnsignedInt(buffer.getShort(entry + 8));
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
        }
        return 1;
    }

    /** Transform already bounded output pixels, including the four mirrored orientations. */
    static BufferedImage apply(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) return source;
        int width = source.getWidth(), height = source.getHeight();
        boolean swap = orientation >= 5;
        var result =
                new BufferedImage(
                        swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int targetX =
                        switch (orientation) {
                            case 2, 3 -> width - 1 - x;
                            case 5, 8 -> y;
                            case 6, 7 -> height - 1 - y;
                            default -> x;
                        };
                int targetY =
                        switch (orientation) {
                            case 3, 4 -> height - 1 - y;
                            case 5, 6 -> x;
                            case 7, 8 -> width - 1 - x;
                            default -> y;
                        };
                result.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        return result;
    }
}
