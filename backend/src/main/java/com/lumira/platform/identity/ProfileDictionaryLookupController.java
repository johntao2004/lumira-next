package com.lumira.platform.identity;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort.Item;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/dictionaries/{code}/items")
public class ProfileDictionaryLookupController {
    private final ProfileDictionaryPort service;

    public ProfileDictionaryLookupController(ProfileDictionaryPort service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<Item> list(
            @PathVariable String code,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search) {
        return service.lookupItems(code, new PageRequest(page, pageSize, search));
    }

    @GetMapping("/{id}")
    public Item find(@PathVariable String code, @PathVariable UUID id) {
        return service.lookupItem(code, id);
    }
}
