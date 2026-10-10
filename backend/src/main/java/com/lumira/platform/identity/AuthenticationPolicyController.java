package com.lumira.platform.identity;

import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.identity.api.AccountOwnerPort;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ContentPolicyExempt
@com.lumira.platform.modules.ModuleEndpoint("identity")
public class AuthenticationPolicyController {
    private final AuthenticationPolicyService policy;
    private final AccountOwnerPort owners;

    public AuthenticationPolicyController(
            AuthenticationPolicyService policy, AccountOwnerPort owners) {
        this.policy = policy;
        this.owners = owners;
    }

    @GetMapping("/api/auth/methods")
    public AuthenticationPolicyService.PublicMethods methods(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return policy.publicMethods();
    }

    @GetMapping("/api/account/authentication-policy")
    public AuthenticationPolicyService.AccountView account(
            Authentication actor, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return policy.accountView(owners.id(actor.getName(), false));
    }
}
