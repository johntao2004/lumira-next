package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Completes the unauthenticated, session-bound forced password-change stage. */
@RestController
@ContentPolicyExempt
@ModuleEndpoint("identity")
@RequestMapping("/api/auth/login")
public class ForcedPasswordChangeController {
    public record ChangeRequest(UUID challengeId, String newPassword, String confirmPassword) {
        @Override
        public String toString() {
            return "ChangeRequest[challengeId="
                    + challengeId
                    + ", newPassword=redacted, confirmPassword=redacted]";
        }
    }

    private final TemporaryPasswordResetService resets;

    public ForcedPasswordChangeController(TemporaryPasswordResetService resets) {
        this.resets = resets;
    }

    @PostMapping("/password-change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "passwordPolicy")
                    ChangeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "secondFactorExpired");
        resets.changePassword(
                body.challengeId(), body.newPassword(), body.confirmPassword(), request);
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
