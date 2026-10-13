package com.lumira.platform.mfa;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ContentPolicyExempt
@ModuleEndpoint("mfa")
@RequestMapping("/api/mfa")
public class MfaController {
    public record SetupRequest(UUID reauthenticationTicketId, String label) {}

    public record ConfirmRequest(UUID challengeId, String code) {}

    public record RenameRequest(UUID reauthenticationTicketId, String label) {}

    public record TicketRequest(UUID reauthenticationTicketId) {}

    public record ResetRequest(UUID reauthenticationTicketId, String reason) {}

    public record CancelReplacementRequest(UUID challengeId) {}

    private final MfaService service;

    public MfaController(MfaService service) {
        this.service = service;
    }

    @GetMapping("/account")
    public MfaResponse.Status status(Authentication actor, HttpServletResponse response) {
        noStore(response);
        return service.status(actor.getName());
    }

    @PostMapping("/account/setup")
    public MfaResponse.Setup setup(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    SetupRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return toResponse(
                service.setup(
                        actor.getName(),
                        body.reauthenticationTicketId(),
                        body.label(),
                        request.getSession().getId()));
    }

    @PostMapping("/account/confirm")
    public MfaResponse.Recovery confirm(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    ConfirmRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return service.confirmSetup(
                actor.getName(), body.challengeId(), body.code(), request.getSession().getId());
    }

    @PostMapping("/account/recovery")
    public MfaResponse.Recovery recovery(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    TicketRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return service.regenerate(
                actor.getName(), body.reauthenticationTicketId(), request.getSession().getId());
    }

    @PutMapping("/account/label")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    RenameRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        service.rename(
                actor.getName(),
                body.reauthenticationTicketId(),
                body.label(),
                request.getSession().getId());
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    TicketRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        service.disable(
                actor.getName(), body.reauthenticationTicketId(), request.getSession().getId());
    }

    @PostMapping("/account/replacement/setup")
    public MfaResponse.Setup replacementSetup(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    SetupRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return toResponse(
                service.replacementSetup(
                        actor.getName(),
                        body.reauthenticationTicketId(),
                        body.label(),
                        request.getSession().getId()));
    }

    @PostMapping("/account/replacement/confirm")
    public MfaResponse.Recovery replacementConfirm(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    ConfirmRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return service.replacementConfirm(
                actor.getName(), body.challengeId(), body.code(), request.getSession().getId());
    }

    @PostMapping("/account/replacement/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replacementCancel(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 2048, errorCode = "secondFactorInvalid")
                    CancelReplacementRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        service.replacementCancel(
                actor.getName(), body.challengeId(), request.getSession().getId());
    }

    @GetMapping("/manage")
    public PageResult<MfaResponse.Account> list(
            Authentication actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search) {
        return service.list(new PageRequest(page, pageSize, search), actor.getName());
    }

    @PostMapping("/manage/{id}/reset")
    public void reset(
            Authentication actor,
            @PathVariable UUID id,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "secondFactorInvalid")
                    ResetRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (body == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        service.reset(
                actor.getName(),
                id,
                body.reauthenticationTicketId(),
                request.getSession().getId(),
                body.reason());
    }

    private static MfaResponse.Setup toResponse(
            com.lumira.platform.identity.api.SecondFactorProvider.EnrollmentSetup setup) {
        return new MfaResponse.Setup(
                setup.challengeId(), setup.secret(), setup.uri(), setup.expiresAt());
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
