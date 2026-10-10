package com.lumira.platform.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ContentPolicyGuard {
    private final ObjectProvider<ContentPolicyPort> policies;

    public ContentPolicyGuard(ObjectProvider<ContentPolicyPort> policies) {
        this.policies = policies;
    }

    public void requireAllowed(String actor, String text, String source) {
        if (actor == null || text == null || text.isBlank()) return;
        boolean blocked = false;
        for (var policy : policies.orderedStream().toList()) {
            blocked |= policy.evaluate(actor, text, source).blocked();
        }
        if (blocked) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sensitiveContent");
    }
}
