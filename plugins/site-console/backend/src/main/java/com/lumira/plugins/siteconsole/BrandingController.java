package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.settings.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
@RequestMapping("/api/platform/settings/branding")
public class BrandingController {
    public record View(long version, Branding data) {}

    public record Input(long version, Branding data, String changeReason) {}

    private final SettingDocuments documents;
    private final BrandingReader reader;
    private final BrandingAssetReferences assetReferences;
    private final AdministrationPort administration;

    public BrandingController(
            SettingDocuments documents,
            BrandingReader reader,
            BrandingAssetReferences assetReferences,
            AdministrationPort administration) {
        this.documents = documents;
        this.reader = reader;
        this.assetReferences = assetReferences;
        this.administration = administration;
    }

    @GetMapping
    public BrandingReader.View read(org.springframework.security.core.Authentication auth) {
        administration.requirePageGlobal(auth.getName(), PlatformPageId.BRANDING);
        return reader.read();
    }

    @PutMapping
    @org.springframework.transaction.annotation.Transactional
    public View write(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidBranding") @RequestBody Input input,
            Authentication auth) {
        var saved =
                documents.save(
                        "branding",
                        input.version(),
                        input.data(),
                        input.changeReason(),
                        auth.getName(),
                        "invalidBranding",
                        Branding::validate);
        assetReferences.retain(saved.data());
        return new View(saved.version(), saved.data());
    }
}
