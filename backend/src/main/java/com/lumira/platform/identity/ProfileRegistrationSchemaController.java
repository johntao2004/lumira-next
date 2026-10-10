package com.lumira.platform.identity;

import com.lumira.platform.identity.api.ProfileRegistrationPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, allow-listed projection for optional custom registration fields. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/auth/registration/profile-schema")
public class ProfileRegistrationSchemaController {
    private final ProfileRegistrationPort registrationProfiles;

    public ProfileRegistrationSchemaController(ProfileRegistrationPort registrationProfiles) {
        this.registrationProfiles = registrationProfiles;
    }

    @GetMapping
    public ProfileRegistrationPort.RegistrationSchema getProfileSchema() {
        return registrationProfiles.registrationSchema();
    }
}
