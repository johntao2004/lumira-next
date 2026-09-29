package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/account/phone")
public class AccountPhoneController {
    private final AccountPhoneService service; private final PhoneVerificationLimiter limiter;
    public AccountPhoneController(AccountPhoneService service, PhoneVerificationLimiter limiter) { this.service=service; this.limiter=limiter; }
    private void rate(String actor) { if (!limiter.allow(actor)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "phoneVerificationLimited"); }
    @GetMapping public AccountPhoneStore.View read(Authentication actor) { return service.read(actor.getName()); }
    @PostMapping("/request") @ResponseStatus(HttpStatus.ACCEPTED)
    public AccountPhoneStore.View request(Authentication actor, @BoundedBody(maxBytes=4096,errorCode="invalidPhone") @RequestBody AccountPhoneService.Request input) {
        rate(actor.getName()); return service.request(actor.getName(), input);
    }
    public record Confirmation(@com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String token) { @Override public String toString() { return "PhoneConfirmation[redacted]"; } }
    @PostMapping("/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(Authentication actor, @BoundedBody(maxBytes=1024,errorCode="invalidVerificationToken") @RequestBody Confirmation input) {
        rate(actor.getName()); boolean confirmed;
        try { confirmed=service.confirm(actor.getName(), input == null ? null : input.token()); }
        catch (DataIntegrityViolationException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "phoneUnavailable"); }
        if (!confirmed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidVerificationToken");
    }
}
