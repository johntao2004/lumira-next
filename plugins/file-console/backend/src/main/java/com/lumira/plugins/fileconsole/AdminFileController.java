package com.lumira.plugins.fileconsole;

import com.lumira.platform.common.FileManagementStoragePort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global file center. Metadata is permission protected; payloads are streamed without buffering.
 */
@com.lumira.platform.modules.ModuleEndpoint("file-console")
@RestController
@RequestMapping("/api/platform/files")
public class AdminFileController {
    private final ManagedFileService files;

    public AdminFileController(ManagedFileService files) {
        this.files = files;
    }

    @GetMapping
    public PageResult<ManagedFileResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication actor) {
        return files.listManaged(
                        actor.getName(),
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters))
                .map(ManagedFileResponse::from);
    }

    @GetMapping("/trash")
    public PageResult<ManagedFileResponse> trash(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication actor) {
        return files.listManagedTrash(
                        actor.getName(),
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters))
                .map(ManagedFileResponse::from);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<FileManagementStoragePort.FilePreviewView> preview(
            @PathVariable UUID id, @RequestParam(defaultValue = "1") int page, Authentication actor)
            throws IOException {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(files.previewManaged(actor.getName(), id, page));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id, Authentication actor)
            throws IOException {
        var entry = files.findManaged(actor.getName(), id);
        var input = files.openManaged(actor.getName(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(entry.sizeBytes() == null ? 0 : entry.sizeBytes())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(entry.originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(input));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication actor) {
        files.deleteManaged(actor.getName(), id, version);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID id, @RequestParam long version, Authentication actor) {
        files.restoreManaged(actor.getName(), id, version);
    }

    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable UUID id, @RequestParam long version, Authentication actor) {
        files.purgeManaged(actor.getName(), id, version);
    }
}
