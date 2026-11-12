package com.lumira.plugins.directory.users;

import com.lumira.plugins.directory.templates.TemplatePdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/users")
public class UserTemplatePrintController {
    private final TemplatePdfService service;

    public UserTemplatePrintController(TemplatePdfService service) {
        this.service = service;
    }

    @PostMapping(value = "/template-print", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> print(
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 32 * 1024,
                            errorCode = "invalidPrintRequest")
                    TemplatePdfService.Request request,
            Authentication auth) {
        var output = service.print(request, auth.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(output.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + output.filename() + "\"")
                .body(output.bytes());
    }
}
