package com.lumira.platform.identity;

import com.lumira.platform.identity.PublicAuthRequests.StrictInput;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/platform/role-preview/bootstrap")
@com.lumira.platform.modules.ModuleEndpoint("identity")
public class RolePreviewBootstrapController {
    public static final class Input extends StrictInput {
        public String username;
        public String operation;
        public String reason;
    }

    private final RolePreviewContextService contexts;

    public RolePreviewBootstrapController(RolePreviewContextService contexts) {
        this.contexts = contexts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public RolePreviewContextService.BootstrapResult apply(
            @RequestBody Input input, HttpServletRequest request) {
        if (input == null || input.hasUnknownFields())
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalidRolePreviewBootstrap");
        return contexts.bootstrap(request, input.username, input.operation, input.reason);
    }
}
