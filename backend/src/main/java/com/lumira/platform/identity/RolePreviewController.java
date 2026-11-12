package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/account/role-preview")
@com.lumira.platform.modules.ModuleEndpoint("identity")
public class RolePreviewController {
    public static final class SelectInput extends PublicAuthRequests.StrictInput {
        public UUID roleId;
        public Long roleVersion;
        public String authorizationContextRevision;
    }

    public static final class ExitInput extends PublicAuthRequests.StrictInput {
        public String authorizationContextRevision;
    }

    private final RolePreviewContextService contexts;

    public RolePreviewController(RolePreviewContextService contexts) {
        this.contexts = contexts;
    }

    @GetMapping
    public RolePreviewContextService.View current(HttpServletRequest request) {
        return contexts.view(request);
    }

    @GetMapping("/roles")
    public PageResult<RolePreviewContextService.Candidate> candidates(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam Map<String, String> parameters,
            HttpServletRequest request) {
        return contexts.candidates(
                request, PageRequest.fromQueryParameters(page, pageSize, search, parameters));
    }

    @PostMapping
    public RolePreviewContextService.View enter(
            @RequestBody @BoundedBody(maxBytes = 2048, errorCode = "invalidRolePreview")
                    SelectInput input,
            HttpServletRequest request,
            Authentication authentication) {
        if (input == null
                || input.hasUnknownFields()
                || input.roleId == null
                || input.roleVersion == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRolePreview");
        return contexts.enter(
                request, input.roleId, input.roleVersion, input.authorizationContextRevision);
    }

    @DeleteMapping
    public RolePreviewContextService.View exit(
            @RequestBody @BoundedBody(maxBytes = 1024, errorCode = "invalidRolePreview")
                    ExitInput input,
            HttpServletRequest request,
            Authentication authentication) {
        if (input == null || input.hasUnknownFields())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRolePreview");
        return contexts.exit(request, input.authorizationContextRevision);
    }
}
