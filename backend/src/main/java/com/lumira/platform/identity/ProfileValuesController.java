package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/account/profile-values")
public class ProfileValuesController {
    private final ProfileValuesService service;

    public ProfileValuesController(ProfileValuesService service) {
        this.service = service;
    }

    @GetMapping
    public ProfileValuesService.View read(Authentication auth) {
        return service.read(auth.getName());
    }

    @PutMapping
    public ProfileValuesService.View write(
            Authentication auth,
            @BoundedBody(maxBytes = 64 * 1024, errorCode = "invalidProfileValues") @RequestBody
                    ProfileValuesService.Input input) {
        return service.write(auth.getName(), input);
    }
}
