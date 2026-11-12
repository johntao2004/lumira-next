package com.lumira.plugins.directory.users;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** XLSX import endpoints. Raw workbooks are delegated to the isolated document worker. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/users/import")
public class UserImportController {
    private static final MediaType XLSX =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final UserXlsxImportService service;

    public UserImportController(UserXlsxImportService service) {
        this.service = service;
    }

    @GetMapping("/fields")
    public List<com.lumira.platform.common.TemplateUserDataPort.Field> fields(Authentication auth) {
        return service.fields(auth.getName());
    }

    @GetMapping("/template.xlsx")
    public ResponseEntity<byte[]> template(Authentication auth) {
        return workbook(service.standardTemplate(auth.getName()), "user-import-template.xlsx");
    }

    @PostMapping(value = "/columns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Columns columns(@RequestParam MultipartFile file, Authentication auth) {
        return new Columns(
                service.columns(file.getOriginalFilename(), bytes(file), auth.getName()));
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserXlsxImportService.PreviewView preview(
            @RequestParam MultipartFile file,
            @RequestParam String mapping,
            @RequestParam String mode,
            Authentication auth) {
        return service.preview(
                file.getOriginalFilename(), bytes(file), mapping, mode, auth.getName());
    }

    @PostMapping(value = "/{previewId}/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserXlsxImportService.TaskView commit(
            @PathVariable UUID previewId,
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 32 * 1024,
                            errorCode = "invalidImportCommit")
                    UserXlsxImportService.CommitRequest request,
            Authentication auth) {
        return service.commit(previewId, request, auth.getName());
    }

    @GetMapping("/{previewId}/errors.xlsx")
    public ResponseEntity<byte[]> errors(@PathVariable UUID previewId, Authentication auth) {
        return workbook(
                service.errorsWorkbook(previewId, auth.getName()), "user-import-errors.xlsx");
    }

    @GetMapping("/tasks/{taskId}")
    public UserXlsxImportService.TaskView task(@PathVariable UUID taskId, Authentication auth) {
        return service.task(taskId, auth.getName());
    }

    private static ResponseEntity<byte[]> workbook(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            if (file.isEmpty())
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "unsupportedFile");
            if (file.getSize() > UserXlsxImportService.MAX_FILE_BYTES)
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "fileTooLarge");
            return file.getBytes();
        } catch (IOException failure) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "unsupportedFile", failure);
        }
    }

    public record Columns(List<UserXlsxImportService.Column> columns) {}
}
