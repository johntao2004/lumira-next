package com.lumira.platform.sensitive;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.platform.sensitive.api.SensitiveWordResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.common.ContentPolicyExempt
@RequestMapping("/api/platform/sensitive-words")
@ModuleEndpoint("sensitive-words")
public class SensitiveWordController {
    private final SensitiveWordService service;

    public SensitiveWordController(SensitiveWordService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<SensitiveWordResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String word,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication auth) {
        PageRequest request = PageRequest.fromQueryParameters(page, pageSize, search, parameters);
        return service.list(
                        request,
                        request.filters().getOrDefault("word", word),
                        request.filters().getOrDefault("category", category),
                        request.filters().getOrDefault("severity", severity),
                        request.filters().getOrDefault("action", action),
                        request.filters().containsKey("enabled")
                                ? Boolean.valueOf(request.filters().get("enabled"))
                                : enabled,
                        auth.getName())
                .map(SensitiveWordResponse::from);
    }

    @GetMapping("/{id}")
    public SensitiveWordResponse find(@PathVariable UUID id, Authentication auth) {
        return SensitiveWordResponse.from(service.find(id, auth.getName()));
    }

    @GetMapping("/options")
    public CategoryOptions options(Authentication auth) {
        return new CategoryOptions(service.categories(auth.getName()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SensitiveWordResponse create(
            @BoundedBody(maxBytes = 4096, errorCode = "invalidSensitiveWord") @RequestBody
                    SensitiveWordService.Input input,
            Authentication auth) {
        return SensitiveWordResponse.from(service.create(input, auth.getName()));
    }

    @PutMapping("/{id}")
    public SensitiveWordResponse update(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 4096, errorCode = "invalidSensitiveWord") @RequestBody
                    SensitiveWordService.Input input,
            Authentication auth) {
        return SensitiveWordResponse.from(service.update(id, input, auth.getName()));
    }

    @PatchMapping("/{id}/status")
    public SensitiveWordResponse status(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 1024, errorCode = "invalidSensitiveWord") @RequestBody
                    StatusInput input,
            Authentication auth) {
        if (input == null || input.enabled() == null || input.version() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        }
        return SensitiveWordResponse.from(
                service.setEnabled(id, input.version(), input.enabled(), auth.getName()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        service.delete(id, version, auth.getName());
    }

    @PostMapping("/import")
    public SensitiveWordService.ImportResult importWords(
            @BoundedBody(maxBytes = 1_000_000, errorCode = "invalidSensitiveImport") @RequestBody
                    SensitiveWordService.ImportRequest input,
            Authentication auth) {
        return service.importWords(input, auth.getName());
    }

    @PostMapping("/check")
    public SensitiveWordService.CheckResult check(
            @BoundedBody(maxBytes = 220_000, errorCode = "sensitiveTextTooLong") @RequestBody
                    CheckInput input,
            Authentication auth) {
        return service.check(input == null ? null : input.text(), auth.getName());
    }

    public record StatusInput(Boolean enabled, Long version) {}

    public record CheckInput(String text) {}

    public record CategoryOptions(List<SensitiveWordService.CategoryOption> categories) {
        public CategoryOptions {
            categories = List.copyOf(categories == null ? List.of() : categories);
        }
    }
}
