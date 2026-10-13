package com.lumira.platform.dictionaries;

import com.lumira.platform.common.*;
import com.lumira.platform.dictionaries.api.DictionaryResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/platform/dictionaries")
public class DictionaryController {
    private final DictionaryService service;

    public DictionaryController(DictionaryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<DictionaryResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication auth) {
        return service.list(
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters),
                        auth.getName())
                .map(DictionaryResponse::from);
    }

    @GetMapping("/{id}")
    public DictionaryResponse find(@PathVariable UUID id, Authentication auth) {
        return DictionaryResponse.from(service.find(id, auth.getName()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryResponse create(
            @RequestBody DictionaryService.Input input, Authentication auth) {
        return DictionaryResponse.from(service.create(input, auth.getName()));
    }

    @PutMapping("/{id}")
    public DictionaryResponse update(
            @PathVariable UUID id,
            @RequestBody DictionaryService.Input input,
            Authentication auth) {
        return DictionaryResponse.from(service.update(id, input, auth.getName()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        service.delete(id, version, auth.getName());
    }
}
