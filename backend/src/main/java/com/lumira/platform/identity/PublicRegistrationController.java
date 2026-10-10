package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous public-registration API; each write retains the platform CSRF protection. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/auth/registration")
public class PublicRegistrationController {
    private final PublicRegistrationService service;

    public PublicRegistrationController(PublicRegistrationService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public PublicRegistrationService.Config config(HttpServletResponse response) {
        noCache(response);
        return service.config();
    }

    @PostMapping("/challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublicRegistrationService.ChallengeView challenge(
            @BoundedBody(maxBytes = 4096, errorCode = "invalidRegistrationChallenge") @RequestBody
                    PublicAuthRequests.Challenge input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        var result = service.requestChallenge(input, request);
        if (result.limited()) {
            response.setHeader("Retry-After", Integer.toString(result.retryAfterSeconds()));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "registrationLimited");
        }
        return result.challenge();
    }

    @PostMapping("/challenges/verify")
    public PublicRegistrationService.VerificationView verify(
            @BoundedBody(maxBytes = 2048, errorCode = "invalidRegistrationChallenge") @RequestBody
                    PublicAuthRequests.Verify input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        var result = service.verifyChallenge(input, request);
        if (!result.valid())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "registrationProofInvalid");
        return result.verification();
    }

    @GetMapping("/state")
    public PublicRegistrationService.RegistrationState state(
            HttpServletRequest request, HttpServletResponse response) {
        noCache(response);
        return service.state(request);
    }

    @PostMapping("/complete")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicRegistrationService.RegistrationReceipt complete(
            @BoundedBody(maxBytes = 128 * 1024, errorCode = "invalidRegistrationRequest")
                    @RequestBody
                    PublicAuthRequests.RegistrationCompletion input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        return service.complete(input, request);
    }

    private static void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
