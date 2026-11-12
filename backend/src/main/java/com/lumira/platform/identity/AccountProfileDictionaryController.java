package com.lumira.platform.identity;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.identity.api.ProfileDictionaryPort.Item;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort.Host;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Self-service options are independent of the optional profile schema management plugin. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/account/profile-dictionaries/{fieldKey}/items")
public class AccountProfileDictionaryController {
    private final ProfileDictionarySelectionPort selections;

    public AccountProfileDictionaryController(ProfileDictionarySelectionPort selections) {
        this.selections = selections;
    }

    @GetMapping
    public PageResult<Item> list(
            @PathVariable String fieldKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search) {
        return selections.list(Host.ACCOUNT, fieldKey, new PageRequest(page, pageSize, search));
    }

    @GetMapping("/{id}")
    public Item find(@PathVariable String fieldKey, @PathVariable UUID id) {
        return selections.find(Host.ACCOUNT, fieldKey, id);
    }
}
