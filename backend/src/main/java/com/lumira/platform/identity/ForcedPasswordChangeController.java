package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.identity.api.LoginResponse;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final SecondFactorLoginService login;

    public ForcedPasswordChangeController(
            TemporaryPasswordResetService resets, SecondFactorLoginService login) {
        this.resets = resets;
        this.login = login;
    }

    @PostMapping("/password-change")
    public LoginResponse change(
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "passwordPolicy")
                    ChangeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "secondFactorExpired");
        return resets.changePasswordAndContinue(
                body.challengeId(),
                body.newPassword(),
                body.confirmPassword(),
                request,
                continuation -> login.continueAfterPasswordChange(continuation, request, response));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
