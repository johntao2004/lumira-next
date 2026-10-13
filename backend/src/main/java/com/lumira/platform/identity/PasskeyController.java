package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stable, browser-facing passkey endpoints for the scaffold. */
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RestController
@RequestMapping("/api/auth/passkeys")
public class PasskeyController {
    private final PasskeyService service;

    public record AuthenticationAvailability(boolean enabled) {}

    public record AuthenticationOptionsInput(String captchaProofId) {}

    public PasskeyController(PasskeyService service) {
        this.service = service;
    }

    @GetMapping("/authentication/availability")
    public AuthenticationAvailability authenticationAvailability() {
        return new AuthenticationAvailability(service.authenticationAvailable());
    }

    @PostMapping("/authentication/options")
    public PasskeyOptions authenticationOptions(
            @BoundedBody(maxBytes = 2_048, errorCode = "invalidPasskeyAuthentication")
                    @RequestBody(required = false)
                    AuthenticationOptionsInput input,
            HttpServletRequest request) {
        return service.authenticationOptions(
                input == null ? null : input.captchaProofId(), request);
    }

    @PostMapping("/authentication/complete")
    public java.util.Map<String, Object> authenticationComplete(
            @BoundedBody(maxBytes = 32_768, errorCode = "invalidPasskeyAuthentication") @RequestBody
                    PasskeyRequests.Authentication input,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        return service.completeAuthentication(input, request, response);
    }

    @PostMapping("/registration/options")
    public PasskeyOptions registrationOptions(
            @BoundedBody(maxBytes = 4_096, errorCode = "invalidPasskeyRegistration")
                    @RequestBody(required = false)
                    PasskeyRequests.Operation input,
            Authentication actor,
            HttpServletRequest request) {
        return service.registrationOptions(actor, input, request);
    }

    @PostMapping("/registration/complete")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public PasskeyCredentialView registrationComplete(
            @BoundedBody(maxBytes = 32_768, errorCode = "invalidPasskeyRegistration") @RequestBody
                    PasskeyRequests.Registration input,
            Authentication actor,
            HttpServletRequest request) {
        return service.completeRegistration(actor, input, request);
    }

    @GetMapping
    public List<PasskeyCredentialView> list(Authentication actor) {
        return service.list(actor);
    }

    public record Rename(String label, String currentPassword) {}

    @PatchMapping("/{id}")
    public PasskeyCredentialView rename(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 4_096, errorCode = "invalidPasskeyLabel") @RequestBody
                    Rename input,
            Authentication actor) {
        return service.rename(
                actor,
                id,
                input == null ? null : input.label(),
                input == null ? null : input.currentPassword());
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 2_048, errorCode = "currentPasswordRequired") @RequestBody
                    PasskeyRequests.Operation input,
            Authentication actor) {
        service.delete(actor, id, input);
    }
}
