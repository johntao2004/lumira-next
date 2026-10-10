package com.lumira.platform.files;

import com.lumira.platform.common.AiAttachmentPort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AiAttachmentReader {
    static AiAttachmentPort.Content read(UUID id, String name, byte[] bytes) {
        if (bytes.length > AiAttachmentPort.MAX_BYTES) throw error("aiAttachmentTooLarge");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try {
            if (extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg")) {
                var image = ImageNormalizer.normalize(new ByteArrayInputStream(bytes), 1536);
                if (image.png().length > AiAttachmentPort.MAX_BYTES)
                    throw error("aiAttachmentTooLarge");
                return new AiAttachmentPort.Content(
                        id,
                        name,
                        bytes.length,
                        null,
                        "data:image/png;base64," + Base64.getEncoder().encodeToString(image.png()),
                        false);
            }
            FilePreview preview =
                    switch (extension) {
                        case "txt", "md", "markdown", "doc", "docx" ->
                                FilePreviewReader.read(name, bytes);
                        case "csv", "json", "log" ->
                                FilePreviewReader.read("attachment.txt", bytes);
                        case "pdf" -> pdf(bytes);
                        default -> throw error("aiAttachmentUnsupported");
                    };
            String text = preview.text();
            if (text.isBlank()) throw error("aiAttachmentEmpty");
            boolean truncated = preview.truncated() || text.length() > AiAttachmentPort.MAX_TEXT;
            if (text.length() > AiAttachmentPort.MAX_TEXT) {
                int end = AiAttachmentPort.MAX_TEXT;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                text = text.substring(0, end);
            }
            return new AiAttachmentPort.Content(id, name, bytes.length, text, null, truncated);
        } catch (ResponseStatusException exception) {
            if (exception.getReason() != null && exception.getReason().startsWith("aiAttachment"))
                throw exception;
            if (exception.getStatusCode().is5xxServerError())
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", exception);
            throw error("aiAttachmentInvalid");
        } catch (IOException | RuntimeException exception) {
            throw error("aiAttachmentInvalid");
        }
    }

    private static FilePreview pdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()
                    || !document.getCurrentAccessPermission().canExtractContent())
                throw error("aiAttachmentInvalid");
            // Bound both the pages visited and the text retained; scanned pages need OCR
            // separately.
            var stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(100, document.getNumberOfPages()));
            var output = new BoundedTextWriter();
            stripper.writeText(document, output);
            return new FilePreview(
                    output.text.toString(), output.truncated || document.getNumberOfPages() > 100);
        }
    }

    private static final class BoundedTextWriter extends Writer {
        final StringBuilder text = new StringBuilder();
        boolean truncated;

        @Override
        public void write(char[] chars, int offset, int length) {
            int count = Math.min(length, AiAttachmentPort.MAX_TEXT + 1 - text.length());
            if (count > 0) text.append(chars, offset, count);
            truncated |= count < length;
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private static ResponseStatusException error(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
