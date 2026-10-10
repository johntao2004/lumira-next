package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fixed callback and same-session browser endpoints for identity-owned WeChat attempts. */
@RestController
@ContentPolicyExempt
@ModuleEndpoint("identity")
public class WechatController {
    private static final String RESULT_PATH = "/user/wechat-result";
    private final WechatIdentityService service;

    public WechatController(WechatIdentityService service) {
        this.service = service;
    }

    @PostMapping("/api/auth/wechat/attempts")
    public WechatIdentityService.StartView start(
            @RequestBody @BoundedBody(maxBytes = 4_096, errorCode = "invalidWechatAttempt")
                    WechatIdentityService.StartInput input,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return service.startPublic(input, request);
    }

    @GetMapping("/api/auth/wechat/callback")
    public void callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false, name = "error") String providerError,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        noStore(response);
        response.setHeader("Referrer-Policy", "no-referrer");
        try {
            service.callback(state, code, providerError, request, response);
        } catch (RuntimeException ignored) {
            // Provider query parameters are sensitive; browser callbacks always land on the safe
            // result page.
        } finally {
            response.setStatus(HttpStatus.SEE_OTHER.value());
            response.setHeader("Location", RESULT_PATH);
        }
    }

    @GetMapping("/api/auth/wechat/result")
    public WechatIdentityService.Result result(
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return service.result(request);
    }

    @GetMapping("/api/account/wechat")
    public WechatIdentityService.AccountView account(
            Authentication actor, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return service.account(actor.getName(), request);
    }

    @PostMapping("/api/account/wechat/binding/start")
    public WechatIdentityService.StartView startBinding(
            @RequestBody @BoundedBody(maxBytes = 2_048, errorCode = "invalidWechatBinding")
                    WechatIdentityService.BindingStart input,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return service.startBinding(actor.getName(), input, request);
    }

    @PostMapping("/api/account/wechat/binding/confirm")
    public WechatIdentityService.AccountView confirmBinding(
            @RequestBody @BoundedBody(maxBytes = 2_048, errorCode = "invalidWechatBinding")
                    WechatIdentityService.BindingConfirmation input,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return service.confirmBinding(actor.getName(), input, request);
    }

    @DeleteMapping("/api/account/wechat")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(
            @RequestBody @BoundedBody(maxBytes = 2_048, errorCode = "invalidWechatBinding")
                    WechatIdentityService.Unbind input,
            Authentication actor,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        service.unbind(actor.getName(), input, request);
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
