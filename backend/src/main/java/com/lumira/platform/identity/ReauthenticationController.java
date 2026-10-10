package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.identity.api.ReauthenticationPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ContentPolicyExempt
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/account/reauthentication")
public class ReauthenticationController {
    public record StartRequest(ReauthenticationPort.Action action) {}

    public record CodeRequest(UUID challengeId, String method) {}

    public record CompleteRequest(
            UUID challengeId,
            String method,
            String password,
            String factorCode,
            UUID codeChallengeId,
            String code) {}

    public record PasskeyOptionsRequest(UUID challengeId) {}

    public record PasskeyCompleteRequest(
            UUID challengeId, PasskeyRequests.Authentication credential, String factorCode) {}

    private final ReauthenticationService service;
    private final PasskeyService passkeys;

    public ReauthenticationController(ReauthenticationService service, PasskeyService passkeys) {
        this.service = service;
        this.passkeys = passkeys;
    }

    @PostMapping("/start")
    public ReauthenticationService.Start start(
            @RequestBody @BoundedBody(maxBytes = 2048, errorCode = "reauthenticationInvalid")
                    StartRequest body,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.start(actor.getName(), body == null ? null : body.action(), request);
    }

    @PostMapping("/complete")
    public ReauthenticationService.Ticket complete(
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "reauthenticationInvalid")
                    CompleteRequest body,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        if ("PASSWORD".equals(body.method())) {
            return service.complete(
                    actor.getName(),
                    body.challengeId(),
                    body.method(),
                    body.password(),
                    body.factorCode(),
                    request);
        }
        if (body.password() != null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        return service.completeCode(
                actor.getName(),
                body.challengeId(),
                body.codeChallengeId(),
                body.method(),
                body.code(),
                body.factorCode(),
                request);
    }

    @PostMapping("/code")
    public ResponseEntity<ReauthenticationService.CodeChallenge> requestCode(
            @RequestBody @BoundedBody(maxBytes = 2048, errorCode = "reauthenticationInvalid")
                    CodeRequest body,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        return ResponseEntity.accepted()
                .body(
                        service.requestCode(
                                actor.getName(), body.challengeId(), body.method(), request));
    }

    @PostMapping("/passkey/options")
    public PasskeyService.ReauthenticationOptions passkeyOptions(
            @RequestBody @BoundedBody(maxBytes = 2048, errorCode = "reauthenticationInvalid")
                    PasskeyOptionsRequest body,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        return passkeys.reauthenticationOptions(actor, body.challengeId(), request);
    }

    @PostMapping("/passkey/complete")
    public ReauthenticationService.Ticket passkeyComplete(
            @RequestBody @BoundedBody(maxBytes = 32_768, errorCode = "reauthenticationInvalid")
                    PasskeyCompleteRequest body,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null || body.credential() == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        var proof =
                passkeys.completeReauthentication(
                        actor, body.challengeId(), body.credential(), request);
        return service.completePasskey(
                actor.getName(), body.challengeId(), proof, body.factorCode(), request);
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
