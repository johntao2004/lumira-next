package com.lumira.platform.settings;

import com.lumira.platform.identity.api.ProfileFields;
import org.springframework.web.bind.annotation.*;

/** Published account projection remains available without the schema editor. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class AccountProfileFieldsController {
    public record View(long version, ProfileFields data) {}

    private final ProfileSchemaReader schemas;

    public AccountProfileFieldsController(ProfileSchemaReader schemas) {
        this.schemas = schemas;
    }

    @GetMapping("/api/account/profile-fields")
    public View accountSchema() {
        var schema = schemas.read();
        return new View(schema.version(), schema.data().forHost("account-profile"));
    }
}
