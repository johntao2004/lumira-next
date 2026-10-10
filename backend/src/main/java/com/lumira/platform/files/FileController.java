package com.lumira.platform.files;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.files.api.FileResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("files")
@RequestMapping("/api/account/files")
public class FileController {
    private final FileService files;
    private final FileUploadRateLimiter limiter;
    private final ClientAddressResolver addresses;

    public FileController(
            FileService files, FileUploadRateLimiter limiter, ClientAddressResolver addresses) {
        this.files = files;
        this.limiter = limiter;
        this.addresses = addresses;
    }

    @GetMapping("/usage")
    public FileService.Usage usage(Authentication actor) {
        return files.usage(actor.getName());
    }

    @GetMapping
    public PageResult<FileResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication actor) {
        return files.list(
                        actor.getName(),
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters))
                .map(FileResponse::from);
    }

    @GetMapping("/trash")
    public PageResult<FileResponse> trash(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication actor) {
        return files.listTrash(
                        actor.getName(),
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters))
                .map(FileResponse::from);
    }

    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse upload(
            @RequestParam String filename, Authentication actor, HttpServletRequest request)
            throws IOException {
        if (!limiter.allow(actor.getName(), addresses.resolve(request)))
            throw new com.lumira.platform.common.RateLimitExceededException("uploadRateLimited");
        if (request.getContentLengthLong() > FileService.MAXIMUM_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "fileTooLarge");
        try {
            return FileResponse.from(
                    files.upload(
                            actor.getName(),
                            filename,
                            request.getInputStream(),
                            request.getContentLengthLong()));
        } catch (BlobStore.TooLarge tooLarge) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "fileTooLarge");
        }
    }

    @GetMapping("/{id}")
    public FileResponse find(@PathVariable UUID id, Authentication actor) {
        return FileResponse.from(files.find(actor.getName(), id));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<FilePreview> preview(
            @PathVariable UUID id, @RequestParam(defaultValue = "1") int page, Authentication actor)
            throws IOException {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(files.preview(actor.getName(), id, page));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id, Authentication actor)
            throws IOException {
        var entry = files.find(actor.getName(), id);
        var input = files.open(entry);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(entry.sizeBytes())
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
        files.delete(actor.getName(), id, version);
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID id, @RequestParam long version, Authentication actor) {
        files.restore(actor.getName(), id, version);
    }

    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable UUID id, @RequestParam long version, Authentication actor) {
        files.purge(actor.getName(), id, version);
    }
}
