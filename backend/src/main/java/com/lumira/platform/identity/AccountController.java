package com.lumira.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/account")
public class AccountController {
    private final PasswordService passwords;
    private final ProfileService profiles;
    public AccountController(PasswordService passwords,ProfileService profiles) { this.passwords = passwords;this.profiles=profiles; }
    @GetMapping("/profile") public ProfileService.View profile(Authentication auth) { return profiles.find(auth.getName()); }
    @PutMapping("/profile") public ProfileService.View updateProfile(@com.lumira.platform.common.BoundedBody(maxBytes=16384,errorCode="invalidProfile") @RequestBody ProfileService.Input input,Authentication auth) { return profiles.update(auth.getName(),input); }
    public record ChangePassword(String currentPassword, String newPassword) {}

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@RequestBody ChangePassword input, Authentication authentication, HttpServletRequest request) {
        passwords.change(authentication.getName(), input.currentPassword(), input.newPassword());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
