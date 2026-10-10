package com.lumira.platform.files;

import com.lumira.platform.common.OfficeDocumentProcessor;
import com.lumira.platform.common.OfficeWorkerClient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Extracts bounded plain text only; file HTML, macros, embedded media, and scripts are never
 * rendered.
 */
final class FilePreviewReader {
    static final int MAX_PREVIEW_CODE_POINTS = OfficeDocumentProcessor.MAX_PREVIEW_CODE_POINTS;

    private FilePreviewReader() {}

    static FilePreview read(String filename, byte[] bytes) {
        return read(filename, bytes, 1);
    }

    static FilePreview read(String filename, byte[] bytes, int page) {
        String extension = extension(filename);
        requireSupported(filename);
        if (bytes.length > FileService.MAXIMUM_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "filePreviewTooLarge");
        }

        try {
            if (page < 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filePreviewInvalid");
            if (extension.equals("doc") || extension.equals("docx")) {
                var preview = OfficeWorkerClient.preview(filename, bytes);
                return new FilePreview(preview.text(), preview.truncated());
            }
            if (extension.equals("pdf")) return FilePreviewMedia.pdf(bytes, page);
            if (extension.equals("png")
                    || extension.equals("jpg")
                    || extension.equals("jpeg")
                    || extension.equals("gif")
                    || extension.equals("bmp")) {
                if (page != 1)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filePreviewInvalid");
                return FilePreviewMedia.image(bytes, extension);
            }
            if (page != 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filePreviewInvalid");
            String text =
                    switch (extension) {
                        case "txt", "md", "markdown", "csv", "json", "log", "xml", "yaml", "yml" ->
                                decodeText(bytes);
                        default ->
                                throw new IllegalStateException(
                                        "Validated preview extension was not handled");
                    };
            return limit(text);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (CharacterCodingException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "filePreviewInvalidEncoding", error);
        } catch (IOException | RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filePreviewInvalid", error);
        }
    }

    static void requireSupported(String filename) {
        String extension = extension(filename);
        if (!java.util.Set.of(
                        "txt",
                        "md",
                        "markdown",
                        "csv",
                        "json",
                        "log",
                        "xml",
                        "yaml",
                        "yml",
                        "doc",
                        "docx",
                        "png",
                        "jpg",
                        "jpeg",
                        "gif",
                        "bmp",
                        "pdf")
                .contains(extension)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "filePreviewUnsupported");
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String decodeText(byte[] bytes) throws CharacterCodingException {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) {
            offset = 3;
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
    }

    private static FilePreview limit(String text) {
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= MAX_PREVIEW_CODE_POINTS) return new FilePreview(text, false);
        return new FilePreview(
                text.substring(0, text.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS)), true);
    }
}
