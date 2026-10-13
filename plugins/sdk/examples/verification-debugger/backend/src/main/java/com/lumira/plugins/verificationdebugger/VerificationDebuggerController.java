package com.lumira.plugins.verificationdebugger;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plugins/verification-debugger")
@ModuleEndpoint("verification-debugger")
public final class VerificationDebuggerController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final VerificationDebuggerService service;

    public VerificationDebuggerController(VerificationDebuggerService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ResponseEntity<VerificationDebuggerService.Status> status(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(service.status(authentication.getName()));
    }

    @PostMapping("/sessions")
    public ResponseEntity<VerificationDebuggerService.SessionResponse> start(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(service.start(authentication.getName()));
    }

    @GetMapping("/messages")
    public ResponseEntity<VerificationDebuggerSessions.MessagePage> messages(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(service.messages(authentication.getName()));
    }

    @DeleteMapping("/sessions")
    public ResponseEntity<Void> end(Authentication authentication) {
        service.end(authentication.getName());
        return ResponseEntity.noContent().cacheControl(NO_STORE).build();
    }

    @PostMapping("/fixtures")
    public ResponseEntity<VerificationDebuggerService.FixtureResult> fixture(
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "debuggerFixtureInvalid")
                    VerificationDebuggerService.FixtureInput input,
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(service.fixture(authentication.getName(), input));
    }

    @PostMapping("/invoke")
    public ResponseEntity<VerificationDebuggerService.InvokeResponse> invoke(
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "debuggerInvokeInvalid")
                    VerificationDebuggerService.InvokeInput input,
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(service.invoke(authentication.getName(), input));
    }
}
