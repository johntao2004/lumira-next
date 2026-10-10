package com.lumira.platform.dictionaries;

import com.lumira.platform.common.*;
import com.lumira.platform.dictionaries.api.DictionaryOptionResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/dictionaries/{code}/items")
public class DictionaryLookupController {
    private final DictionaryLookupService service;

    public DictionaryLookupController(DictionaryLookupService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<DictionaryOptionResponse> list(
            @PathVariable String code,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search) {
        return service.list(code, new PageRequest(page, pageSize, search))
                .map(DictionaryOptionResponse::from);
    }

    @GetMapping("/{id}")
    public DictionaryOptionResponse find(@PathVariable String code, @PathVariable UUID id) {
        return DictionaryOptionResponse.from(service.find(code, id));
    }
}
