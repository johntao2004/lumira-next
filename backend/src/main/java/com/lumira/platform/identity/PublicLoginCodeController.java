package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous, CSRF-protected OTP login routes with account-neutral challenge responses. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/auth/login-code")
public class PublicLoginCodeController {
    private final PublicLoginCodeService service;

    public PublicLoginCodeController(PublicLoginCodeService service) {
        this.service = service;
    }

    @PostMapping("/challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublicLoginCodeService.ChallengeView challenge(
            @BoundedBody(maxBytes = 4096, errorCode = "invalidLoginCodeChallenge") @RequestBody
                    PublicAuthRequests.Challenge input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        var result = service.requestChallenge(input, request);
        if (result.limited()) {
            response.setHeader("Retry-After", Integer.toString(result.retryAfterSeconds()));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "loginCodeLimited");
        }
        return result.challenge();
    }

    @PostMapping("/complete")
    public com.lumira.platform.identity.api.LoginResponse complete(
            @BoundedBody(maxBytes = 2048, errorCode = "invalidLoginCode") @RequestBody
                    PublicAuthRequests.LoginCodeCompletion input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noCache(response);
        var result = service.complete(input, request, response);
        if (!result.valid())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "loginCodeInvalid");
        return result.response();
    }

    private static void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
