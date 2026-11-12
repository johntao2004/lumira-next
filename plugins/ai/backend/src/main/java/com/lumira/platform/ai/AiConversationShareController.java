package com.lumira.platform.ai;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.modules.ModuleEndpoint;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@ModuleEndpoint("ai")
public class AiConversationShareController {
    private final AiConversationShareService shares;

    public AiConversationShareController(AiConversationShareService shares) {
        this.shares = shares;
    }

    @PostMapping("/api/platform/ai/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public AiConversationShareService.CreateResult create(
            @BoundedBody(maxBytes = 4 * 1024 * 1024, errorCode = "aiShareTooLarge") @RequestBody
                    JsonNode input,
            Authentication auth,
            HttpServletResponse response) {
        applySafetyHeaders(response);
        return shares.create(input, auth.getName());
    }

    @GetMapping("/api/platform/ai/shares")
    public PageResult<AiConversationShareService.ShareSummary> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication auth,
            HttpServletResponse response) {
        applySafetyHeaders(response);
        return shares.list(new PageRequest(page, pageSize, ""), auth.getName());
    }

    @DeleteMapping("/api/platform/ai/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, Authentication auth, HttpServletResponse response) {
        applySafetyHeaders(response);
        shares.revoke(id, auth.getName());
    }

    @PutMapping("/api/platform/ai/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 4 * 1024 * 1024, errorCode = "aiShareTooLarge") @RequestBody
                    JsonNode input,
            Authentication auth,
            HttpServletResponse response) {
        applySafetyHeaders(response);
        shares.update(id, input, auth.getName());
    }

    @GetMapping("/api/public/ai/shares/{token}")
    public AiConversationShareService.SharedConversation read(
            @PathVariable String token, HttpServletResponse response) {
        applySafetyHeaders(response);
        return shares.read(token);
    }

    private static void applySafetyHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
    }
}
