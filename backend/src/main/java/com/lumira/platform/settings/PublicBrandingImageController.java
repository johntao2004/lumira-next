package com.lumira.platform.settings;

import com.lumira.platform.common.FileContentPort;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class PublicBrandingImageController {
    private final FileContentPort files;
    private final BrandingReader branding;

    public PublicBrandingImageController(FileContentPort files, BrandingReader branding) {
        this.files = files;
        this.branding = branding;
    }

    /** The immutable file is public only while one of the current branding fields names it. */
    @GetMapping("/api/public/branding/assets/{id}")
    public ResponseEntity<InputStreamResource> image(@PathVariable UUID id) throws IOException {
        String url = BrandingAssetReferences.PUBLIC_ASSET_PREFIX + id;
        Branding current = branding.read().data();
        if (!url.equals(current.websiteFaviconUrl())
                && !url.equals(current.websiteLogoUrl())
                && !url.equals(current.loginBackgroundUrl())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound");
        }
        var image = files.findReadyBrandingImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(image.sizeBytes())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(files.openContentImage(image)));
    }
}
