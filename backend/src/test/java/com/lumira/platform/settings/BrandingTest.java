package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BrandingTest {
    private Branding withUrl(String url) {
        return new Branding("Site", url, "", "", false, "", false, "", "Company", 2026, "", "", "");
    }

    @Test
    void acceptsDefaultsAndSafeAssetLocations() {
        Branding.defaults().validate();
        withUrl("/assets/logo.svg").validate();
        withUrl("https://example.com/logo.png").validate();
    }

    @Test
    void rejectsExecutableAndAmbiguousLocations() {
        for (String url :
                new String[] {
                    "javascript:alert(1)",
                    "data:image/svg+xml,hello",
                    "//example.com/a",
                    "/\\example.com/a",
                    "https://user:pass@example.com/a",
                    "/logo\n.svg"
                }) assertThrows(ResponseStatusException.class, () -> withUrl(url).validate());
    }

    @Test
    void rejectsMissingRequiredValues() {
        assertThrows(
                ResponseStatusException.class,
                () ->
                        new Branding(
                                        " ", "", "", "", false, "", false, "", "Company", 2026, "",
                                        "", "")
                                .validate());
    }
}
