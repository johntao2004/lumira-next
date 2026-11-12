package com.lumira.plugins.directory.templates;

import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.TemplateUserDataPort;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/templates")
public class TemplateController {
    public record Bindings(long version, Map<String, String> bindings) {}

    public record Metadata(long version, String name, String category, List<String> tags) {}

    public record VersionRequest(long version) {}

    public record PreviewRequest(int templateVersion, UUID userId, Map<String, String> values) {}

    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<TemplateService.Summary> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam(defaultValue = "") String status,
            Authentication auth) {
        return service.list(page, pageSize, search, category, tag, status, auth.getName());
    }

    @GetMapping("/fields")
    public List<TemplateUserDataPort.Field> fields(Authentication auth) {
        return service.fields(auth.getName());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.CREATED)
    public TemplateService.Detail create(
            @RequestParam MultipartFile file,
            @RequestParam String name,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, name = "tags[]") List<String> tags,
            @RequestParam(required = false, name = "tags") List<String> plainTags,
            @RequestParam String bindingMode,
            Authentication auth) {
        return service.create(
                name,
                category,
                tags == null ? plainTags : tags,
                bindingMode,
                file.getOriginalFilename(),
                bytes(file),
                auth.getName());
    }

    @GetMapping("/{id}")
    public TemplateService.Detail find(@PathVariable UUID id, Authentication auth) {
        return service.find(id, auth.getName());
    }

    @GetMapping("/{id}/versions")
    public List<TemplateService.VersionInfo> versions(@PathVariable UUID id, Authentication auth) {
        return service.versions(id, auth.getName());
    }

    @PutMapping("/{id}")
    public TemplateService.Detail update(
            @PathVariable UUID id, @RequestBody Metadata input, Authentication auth) {
        return service.updateMetadata(
                id, input.version(), input.name(), input.category(), input.tags(), auth.getName());
    }

    @PostMapping(
            value = "/{id}/versions/draft/source",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TemplateService.Detail newDraft(
            @PathVariable UUID id,
            @RequestParam long version,
            @RequestParam MultipartFile file,
            Authentication auth) {
        return service.newDraft(
                id, version, file.getOriginalFilename(), bytes(file), auth.getName());
    }

    @PutMapping("/{id}/versions/draft")
    public TemplateService.Detail updateBindings(
            @PathVariable UUID id, @RequestBody Bindings input, Authentication auth) {
        return service.updateBindings(id, input.version(), input.bindings(), auth.getName());
    }

    @PostMapping("/{id}/publish")
    public TemplateService.Detail publish(
            @PathVariable UUID id, @RequestBody VersionRequest input, Authentication auth) {
        return service.publish(id, input.version(), auth.getName());
    }

    @PostMapping("/{id}/deactivate")
    public TemplateService.Detail deactivate(
            @PathVariable UUID id, @RequestBody VersionRequest input, Authentication auth) {
        return service.deactivate(id, input.version(), auth.getName());
    }

    @PostMapping(
            value = "/{id}/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/pdf")
    public ResponseEntity<byte[]> preview(
            @PathVariable UUID id,
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 256 * 1024,
                            errorCode = "invalidTemplatePreview")
                    PreviewRequest input,
            Authentication auth) {
        byte[] pdf =
                service.preview(
                        id,
                        input.templateVersion(),
                        input.userId(),
                        input.values(),
                        auth.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=template-preview.pdf")
                .body(pdf);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            if (file.isEmpty() || file.getSize() > TemplateService.MAX_TEMPLATE_BYTES)
                throw new org.springframework.web.server.ResponseStatusException(
                        file.getSize() > TemplateService.MAX_TEMPLATE_BYTES
                                ? org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE
                                : org.springframework.http.HttpStatus.BAD_REQUEST,
                        "unsupportedFile");
            return file.getBytes();
        } catch (IOException failure) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "unsupportedFile", failure);
        }
    }
}
