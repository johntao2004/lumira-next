package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ContentPolicyExempt
@ModuleEndpoint("identity")
@RequestMapping("/api/auth/captcha")
public class CaptchaController {
    private final CaptchaProofService service;

    public CaptchaController(CaptchaProofService service) {
        this.service = service;
    }

    @PostMapping("/challenges")
    public CaptchaProofService.ChallengeResponse challenge(
            @RequestBody @BoundedBody(maxBytes = 2_048, errorCode = "captchaActionInvalid")
                    CaptchaProofService.ChallengeRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.start(input, request);
    }

    @PostMapping("/verify")
    public CaptchaProofService.VerifyResponse verify(
            @RequestBody @BoundedBody(maxBytes = 8_192, errorCode = "captchaInvalid")
                    CaptchaProofService.VerifyRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.verify(input, request);
    }
}
