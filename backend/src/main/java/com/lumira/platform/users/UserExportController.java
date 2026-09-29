package com.lumira.platform.users;

import com.lumira.platform.common.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Binary export endpoint kept separate from the id-based user resource route. */
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RestController
@RequestMapping("/api/platform/users/export")
public class UserExportController {
    private final UserService service;

    public UserExportController(UserService service) {
        this.service = service;
    }

    @GetMapping(produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String search,
                                         @RequestParam java.util.Map<String, String> parameters,
                                         Authentication auth) {
        PageRequest request = PageRequest.fromQueryParameters(1, 100, search, parameters);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.exportCsv(request, auth.getName()));
    }
}
