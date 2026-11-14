package com.lumira.example.notes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 中性笔记 API 明确区分读取与写入，服务端逐次执行授权。 Neutral notes API separates read and write grants and enforces
 * authorization on every server request.
 */
@RestController
class NotesController {
    private final NotesStore store;

    NotesController(NotesStore store) {
        this.store = store;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "header", token.getHeaderName());
    }

    @GetMapping("/notes")
    List<Map<String, Object>> list(Authentication actor) {
        require(actor, "notes:read");
        return store.list();
    }

    @PostMapping("/notes")
    Map<String, UUID> create(Authentication actor, @RequestBody Map<String, String> body) {
        require(actor, "notes:write");
        try {
            return Map.of("id", store.create(body.get("text")));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidNote");
        }
    }

    private static void require(Authentication actor, String permission) {
        if (actor == null
                || actor.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals(permission)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
