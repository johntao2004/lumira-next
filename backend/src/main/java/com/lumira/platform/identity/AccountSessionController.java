package com.lumira.platform.identity;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/account/sessions")
public class AccountSessionController {
    private final AccountSessionService service;

    public AccountSessionController(AccountSessionService service) {
        this.service = service;
    }

    private String currentId(HttpServletRequest request) {
        var session = request.getSession(false);
        return session == null ? "" : session.getId();
    }

    @GetMapping
    public PageResult<SessionResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication auth,
            HttpServletRequest request) {
        return service.list(
                        auth.getName(),
                        currentId(request),
                        PageRequest.fromQueryParameters(page, pageSize, "", parameters))
                .map(SessionResponse::from);
    }

    @DeleteMapping("/others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void others(Authentication auth, HttpServletRequest request) {
        service.deleteOthers(auth.getName(), currentId(request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        if (service.delete(auth.getName(), id, currentId(request))) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
    }
}
