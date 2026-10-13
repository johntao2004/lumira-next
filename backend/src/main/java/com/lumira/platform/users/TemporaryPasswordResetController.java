package com.lumira.platform.users;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.identity.api.TemporaryPasswordResetPort;
import com.lumira.platform.modules.ModuleEndpoint;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Version-checked administration endpoint for issuing one-time temporary passwords. */
@RestController
@ContentPolicyExempt
@ModuleEndpoint("directory")
@RequestMapping("/api/platform/users")
public class TemporaryPasswordResetController {
    public record ResetRequest(Long version) {}

    private final TemporaryPasswordResetPort resets;

    public TemporaryPasswordResetController(TemporaryPasswordResetPort resets) {
        this.resets = resets;
    }

    @PostMapping("/{userId}/password-reset")
    public TemporaryPasswordResetPort.ResetResult reset(
            @PathVariable UUID userId,
            @RequestBody @BoundedBody(maxBytes = 1024, errorCode = "invalidPasswordReset")
                    ResetRequest body,
            Authentication actor,
            jakarta.servlet.http.HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidPasswordReset");
        return resets.reset(userId, body.version(), actor == null ? null : actor.getName());
    }

    private static void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
