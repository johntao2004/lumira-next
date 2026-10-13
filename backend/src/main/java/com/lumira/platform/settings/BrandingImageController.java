package com.lumira.platform.settings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FileContentPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.RateLimitExceededException;
import com.lumira.platform.common.UploadRateLimitPort;
import com.lumira.platform.settings.api.BrandingAssetResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class BrandingImageController {
    private final FileContentPort files;
    private final BrandingReader branding;
    private final UploadRateLimitPort uploadLimits;
    private final ClientAddressResolver addresses;
    private final AdministrationPort administration;

    public BrandingImageController(
            FileContentPort files,
            BrandingReader branding,
            UploadRateLimitPort uploadLimits,
            ClientAddressResolver addresses,
            AdministrationPort administration) {
        this.files = files;
        this.branding = branding;
        this.uploadLimits = uploadLimits;
        this.addresses = addresses;
        this.administration = administration;
    }

    @PostMapping(
            path = "/api/platform/settings/branding/assets",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public BrandingAssetResponse upload(
            @RequestParam FileContentPort.BrandingImageSlot slot,
            Authentication actor,
            HttpServletRequest request)
            throws IOException {
        administration.requireOperationGlobal(actor.getName(), PlatformOperation.BRANDING_UPDATE);
        if (!uploadLimits.allow(actor.getName(), addresses.resolve(request)))
            throw new RateLimitExceededException("uploadRateLimited");
        if (request.getContentLengthLong() > FileContentPort.MAXIMUM_BRANDING_IMAGE_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "imageTooLarge");
        try {
            var image =
                    files.uploadBrandingImage(
                            actor.getName(),
                            slot,
                            request.getInputStream(),
                            request.getContentLengthLong());
            return new BrandingAssetResponse(
                    BrandingAssetReferences.PUBLIC_ASSET_PREFIX + image.id(), image.sizeBytes());
        } catch (FileContentPort.InvalidImage invalid) {
            HttpStatus status =
                    invalid.getMessage().equals("imageTooLarge")
                            ? HttpStatus.PAYLOAD_TOO_LARGE
                            : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, invalid.getMessage(), invalid);
        }
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
