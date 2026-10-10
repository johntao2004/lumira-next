package com.lumira.platform.settings;

import com.lumira.platform.common.FileContentPort;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Registers image references carried by the current branding document and its revisions. */
@Service
public class BrandingAssetReferences {
    public static final String PUBLIC_ASSET_PREFIX = "/api/public/branding/assets/";

    private final FileContentPort files;

    public BrandingAssetReferences(FileContentPort files) {
        this.files = files;
    }

    public void retain(Branding branding) {
        retainUrl(branding.websiteFaviconUrl());
        retainUrl(branding.websiteLogoUrl());
        retainUrl(branding.loginBackgroundUrl());
    }

    private void retainUrl(String url) {
        UUID id = imageId(url);
        if (id != null) files.retainBrandingImage(id);
    }

    public static UUID imageId(String url) {
        if (url == null || !url.startsWith(PUBLIC_ASSET_PREFIX)) return null;
        String value = url.substring(PUBLIC_ASSET_PREFIX.length());
        try {
            UUID id = UUID.fromString(value);
            return (PUBLIC_ASSET_PREFIX + id).equals(url) ? id : null;
        } catch (IllegalArgumentException invalidId) {
            return null;
        }
    }
}
