package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.identity.api.LoginResponse;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public, session-bound read and cancel endpoints for a pending primary-authentication attempt. */
@RestController
@ContentPolicyExempt
@ModuleEndpoint("identity")
@RequestMapping("/api/auth/login")
public class LoginStateController {
    public record CancelRequest(UUID challengeId) {}

    public record CaptchaConfirmation(UUID challengeId, String captchaProofId) {}

    private final SecondFactorLoginService service;

    public LoginStateController(SecondFactorLoginService service) {
        this.service = service;
    }

    @GetMapping("/state")
    public LoginResponse state(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        return service.pending(request);
    }

    @PostMapping("/captcha/confirm")
    public LoginResponse confirmCaptcha(
            @RequestBody @BoundedBody(maxBytes = 2048, errorCode = "captchaRequired")
                    CaptchaConfirmation body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "captchaRequired");
        return service.confirmCaptcha(body.challengeId(), body.captchaProofId(), request, response);
    }

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @RequestBody @BoundedBody(maxBytes = 1024, errorCode = "secondFactorExpired")
                    CancelRequest body,
            HttpServletRequest request) {
        service.cancel(body == null ? null : body.challengeId(), request);
    }
}
