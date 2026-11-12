package com.lumira.plugins.directory.users;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.identity.api.ProfileDictionaryPort.Item;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort.Host;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Uses the same target scope as the editor's profile-values projection. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/users/{userId}/profile-dictionaries/{fieldKey}/items")
public class UserProfileDictionaryController {
    private final UserService users;
    private final ProfileDictionarySelectionPort selections;

    public UserProfileDictionaryController(
            UserService users, ProfileDictionarySelectionPort selections) {
        this.users = users;
        this.selections = selections;
    }

    @GetMapping
    public PageResult<Item> list(
            @PathVariable UUID userId,
            @PathVariable String fieldKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            Authentication auth) {
        users.find(userId, auth.getName());
        return selections.list(Host.USER_EDITOR, fieldKey, new PageRequest(page, pageSize, search));
    }

    @GetMapping("/{id}")
    public Item find(
            @PathVariable UUID userId,
            @PathVariable String fieldKey,
            @PathVariable UUID id,
            Authentication auth) {
        users.find(userId, auth.getName());
        return selections.find(Host.USER_EDITOR, fieldKey, id);
    }
}
