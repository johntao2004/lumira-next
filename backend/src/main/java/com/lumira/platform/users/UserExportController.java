package com.lumira.platform.users;

import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** XLSX-only user export endpoints. */
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RestController
@RequestMapping("/api/platform/users")
public class UserExportController {
    private static final MediaType XLSX =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final UserXlsxExportService service;

    public UserExportController(UserXlsxExportService service) {
        this.service = service;
    }

    @GetMapping("/export/fields")
    public java.util.List<UserXlsxExportService.Field> fields(Authentication auth) {
        return service.fields(auth.getName());
    }

    @PostMapping(value = "/export.xlsx", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> export(
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 256 * 1024,
                            errorCode = "invalidExportRequest")
                    UserXlsxExportService.Request request,
            Authentication auth) {
        RequestAuthorizationContext authorization =
                AuthenticationRequestContext.current() == null
                        ? null
                        : AuthenticationRequestContext.current().authorization();
        byte[] workbook = service.export(request, auth.getName(), authorization);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.xlsx\"")
                .body(workbook);
    }
}
