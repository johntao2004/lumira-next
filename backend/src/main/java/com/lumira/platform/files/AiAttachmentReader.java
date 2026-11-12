package com.lumira.platform.files;

import com.lumira.platform.common.AiAttachmentPort;
import com.lumira.platform.common.OfficeWorkerClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
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
                        case "pdf" -> {
                            var previewText = OfficeWorkerClient.preview(name, bytes);
                            yield new FilePreview(previewText.text(), previewText.truncated());
                        }
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

    private static ResponseStatusException error(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
