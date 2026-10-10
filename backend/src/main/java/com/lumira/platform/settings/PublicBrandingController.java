package com.lumira.platform.settings;

import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class PublicBrandingController {
    private final BrandingReader reader;

    public PublicBrandingController(BrandingReader reader) {
        this.reader = reader;
    }

    @GetMapping("/api/public/branding")
    public Branding read() {
        return reader.read().data();
    }
}
