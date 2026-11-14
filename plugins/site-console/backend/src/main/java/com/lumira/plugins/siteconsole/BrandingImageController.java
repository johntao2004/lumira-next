package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.ClientAddressPort;
import com.lumira.platform.common.FileContentPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.RateLimitExceededException;
import com.lumira.platform.common.UploadRateLimitPort;
import com.lumira.platform.settings.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
public class BrandingImageController {
    private final FileContentPort files;
    private final UploadRateLimitPort uploadLimits;
    private final ClientAddressPort addresses;
    private final AdministrationPort administration;

    public BrandingImageController(
            FileContentPort files,
            UploadRateLimitPort uploadLimits,
            ClientAddressPort addresses,
            AdministrationPort administration) {
        this.files = files;
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
}
