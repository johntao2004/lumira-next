package com.lumira.platform.identity;

import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.identity.api.LoginResponse;
import com.lumira.platform.identity.api.SecondFactorProvider;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@ContentPolicyExempt
@ModuleEndpoint("identity")
@RequestMapping("/api/auth/second-factor")
public class SecondFactorController {
    private final SecondFactorLoginService service;

    public SecondFactorController(SecondFactorLoginService service) {
        this.service = service;
    }

    public record Confirmation(UUID challengeId, String code) {}

    public record EnrollmentStart(UUID challengeId, String label) {}

    public record EnrollmentConfirmation(UUID challengeId, String code) {}

    public record RecoveryRequest(UUID challengeId) {}

    public record RecoveryVerification(UUID challengeId, String recoveryTicket) {}

    @GetMapping("/pending")
    public LoginResponse pending(HttpServletRequest req) {
        return service.pending(req);
    }

    @PostMapping("/enrollment/start")
    public SecondFactorProvider.EnrollmentSetup startEnrollment(
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 4096,
                            errorCode = "secondFactorInvalid")
                    EnrollmentStart body,
            HttpServletRequest req,
            HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store");
        return service.startEnrollment(
                body == null ? null : body.challengeId(), body == null ? null : body.label(), req);
    }

    @PostMapping("/enrollment/confirm")
    public SecondFactorLoginService.EnrollmentConfirmationResponse confirmEnrollment(
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 4096,
                            errorCode = "secondFactorInvalid")
                    EnrollmentConfirmation body,
            HttpServletRequest req,
            HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store");
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return service.confirmEnrollment(body.challengeId(), body.code(), req, res);
    }

    @PostMapping("/confirm")
    public LoginResponse confirm(
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 4096,
                            errorCode = "secondFactorInvalid")
                    Confirmation body,
            HttpServletRequest req,
            HttpServletResponse res) {
        return service.confirm(body.challengeId(), body.code(), req, res);
    }

    @PostMapping("/recovery/request")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public SecondFactorProvider.RecoveryRequest requestRecovery(
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 2048,
                            errorCode = "secondFactorInvalid")
                    RecoveryRequest body,
            HttpServletRequest req,
            HttpServletResponse res) {
        noStore(res);
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return service.requestRecovery(body.challengeId(), req);
    }

    @PostMapping("/recovery/verify")
    public LoginResponse verifyRecovery(
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 2048,
                            errorCode = "secondFactorInvalid")
                    RecoveryVerification body,
            HttpServletRequest req,
            HttpServletResponse res) {
        noStore(res);
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return service.verifyRecovery(body.challengeId(), body.recoveryTicket(), req, res);
    }

    @PostMapping("/cancel")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancel(HttpServletRequest req) {
        service.cancel(req);
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
