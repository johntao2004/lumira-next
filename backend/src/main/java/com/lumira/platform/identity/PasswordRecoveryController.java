package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ClientAddressResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous, CSRF-protected recovery routes; email token reset remains backward compatible. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/auth/password-recovery")
public class PasswordRecoveryController {
    private final PasswordRecoveryService emailRecovery;
    private final SmsPasswordRecoveryService smsRecovery;
    private final PasswordRecoveryLimiter emailLimiter;
    private final ClientAddressResolver addresses;

    public PasswordRecoveryController(
            PasswordRecoveryService emailRecovery,
            SmsPasswordRecoveryService smsRecovery,
            PasswordRecoveryLimiter emailLimiter,
            ClientAddressResolver addresses) {
        this.emailRecovery = emailRecovery;
        this.smsRecovery = smsRecovery;
        this.emailLimiter = emailLimiter;
        this.addresses = addresses;
    }

    @PostMapping("/request")
    public ResponseEntity<?> request(
            @BoundedBody(maxBytes = 4096, errorCode = "invalidRecoveryRequest") @RequestBody
                    PublicAuthRequests.RecoveryRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        if (input == null || input.hasUnknownFields()) throw badRequest("invalidRecoveryRequest");
        if ("SMS".equals(input.channel)) {
            if (input.username != null && !input.username.isBlank())
                throw badRequest("invalidRecoveryRequest");
            var result = smsRecovery.requestChallenge(input, request);
            if (result.limited()) {
                response.setHeader("Retry-After", Integer.toString(result.retryAfterSeconds()));
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "passwordRecoveryLimited");
            }
            return ResponseEntity.accepted().body(result.challenge());
        }
        if (input.channel != null && !"EMAIL".equals(input.channel))
            throw badRequest("invalidRecoveryRequest");
        if (input.contact != null && !input.contact.isBlank())
            throw badRequest("invalidRecoveryRequest");
        checkLegacy(input.username, request);
        emailRecovery.request(input.username, input.locale, input.captchaProofId, request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/state")
    public SmsPasswordRecoveryService.State state(
            HttpServletRequest request, HttpServletResponse response) {
        noCache(response);
        return smsRecovery.state(request);
    }

    @PostMapping("/verify")
    public SmsPasswordRecoveryService.VerificationView verify(
            @BoundedBody(maxBytes = 2048, errorCode = "invalidRecoveryRequest") @RequestBody
                    PublicAuthRequests.RecoveryVerify input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        var result = smsRecovery.verifyChallenge(input, request);
        if (!result.valid()) throw badRequest("passwordRecoveryCodeInvalid");
        return result.verification();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(
            @BoundedBody(maxBytes = 4096, errorCode = "invalidRecoveryRequest") @RequestBody
                    PublicAuthRequests.RecoveryConfirmation input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        if (input == null || input.hasUnknownFields()) throw badRequest("invalidRecoveryRequest");
        if (input.proofId != null) {
            if (input.username != null || input.token != null)
                throw badRequest("invalidRecoveryRequest");
            if (!smsRecovery.confirm(input, request))
                throw badRequest("passwordRecoveryProofInvalid");
            return ResponseEntity.noContent().build();
        }
        if (input.username == null || input.token == null)
            throw badRequest("invalidRecoveryRequest");
        checkLegacy(input.username, request);
        if (!emailRecovery.confirm(
                input.username, input.token, input.newPassword, input.captchaProofId, request))
            throw badRequest("invalidRecoveryToken");
        return ResponseEntity.noContent().build();
    }

    private void checkLegacy(String username, HttpServletRequest request) {
        if (username == null || username.isBlank() || username.length() > 100)
            throw badRequest("invalidRecoveryRequest");
        if (!emailLimiter.allow(username, addresses.resolve(request)))
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "passwordRecoveryLimited");
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
