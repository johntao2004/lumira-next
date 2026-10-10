package com.lumira.platform.files;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/account/role-preview/resource-tickets")
@com.lumira.platform.modules.ModuleEndpoint("files")
public class FilePreviewResourceTicketController {
    public static final class IssueInput {
        private final Set<String> unknown = new LinkedHashSet<>();
        public String path;
        public String authorizationContextRevision;

        @JsonAnySetter
        public void unknown(String key, Object ignored) {
            unknown.add(key);
        }

        public boolean hasUnknownFields() {
            return !unknown.isEmpty();
        }
    }

    private final FilePreviewResourceTicketService tickets;

    public FilePreviewResourceTicketController(FilePreviewResourceTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    public FilePreviewResourceTicketService.Ticket issue(@RequestBody IssueInput input) {
        if (input == null || input.hasUnknownFields())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidResourceTicket");
        return tickets.issue(input.path, input.authorizationContextRevision);
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<FilePreview> read(@PathVariable UUID ticketId) throws IOException {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(tickets.consumeAndRead(ticketId));
    }
}
