package com.lumira.platform.files;

import com.lumira.platform.common.AiAttachmentPort;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiAttachmentAdapter implements AiAttachmentPort {
    private final FileService files;

    public AiAttachmentAdapter(FileService files) {
        this.files = files;
    }

    @Override
    public Content read(String actor, UUID id) {
        var entry = files.find(actor, id);
        if (entry.sizeBytes() != null && entry.sizeBytes() > MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiAttachmentTooLarge");
        try (var admission = FilePreviewReader.admit();
                var input = files.open(entry)) {
            return AiAttachmentReader.read(
                    id, entry.originalName(), input.readNBytes(MAX_BYTES + 1));
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "aiAttachmentInvalid", exception);
        }
    }
}
