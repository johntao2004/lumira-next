package com.lumira.plugins.dictionaries;

import com.lumira.platform.common.*;
import com.lumira.plugins.dictionaries.api.DictionaryItemResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/platform/dictionaries/{dictionary}/items")
public class DictionaryItemController {
    private final DictionaryItemService service;

    public DictionaryItemController(DictionaryItemService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<DictionaryItemResponse> list(
            @PathVariable UUID dictionary,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String parent,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication auth) {
        return service.list(
                        dictionary,
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters),
                        parent,
                        auth.getName())
                .map(DictionaryItemResponse::from);
    }

    @GetMapping("/{id}/ancestors")
    public java.util.List<DictionaryItemResponse> ancestors(
            @PathVariable UUID dictionary, @PathVariable UUID id, Authentication auth) {
        return service.ancestors(dictionary, id, auth.getName()).stream()
                .map(DictionaryItemResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public DictionaryItemResponse find(
            @PathVariable UUID dictionary, @PathVariable UUID id, Authentication auth) {
        return DictionaryItemResponse.from(service.find(dictionary, id, auth.getName()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryItemResponse create(
            @PathVariable UUID dictionary,
            @RequestBody DictionaryItemService.Input input,
            Authentication auth) {
        return DictionaryItemResponse.from(service.create(dictionary, input, auth.getName()));
    }

    @PutMapping("/{id}")
    public DictionaryItemResponse update(
            @PathVariable UUID dictionary,
            @PathVariable UUID id,
            @RequestBody DictionaryItemService.Input input,
            Authentication auth) {
        return DictionaryItemResponse.from(service.update(dictionary, id, input, auth.getName()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID dictionary,
            @PathVariable UUID id,
            @RequestParam long version,
            Authentication auth) {
        service.delete(dictionary, id, version, auth.getName());
    }
}
