package com.lumira.platform.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record Branding(
        String websiteName,
        String websiteFaviconUrl,
        String websiteLogoUrl,
        String loginBackgroundUrl,
        Boolean githubLinkEnabled,
        String githubLinkUrl,
        Boolean helpLinkEnabled,
        String helpLinkUrl,
        String companyName,
        Integer copyrightStartYear,
        String footerIcp,
        String footerPoliceBeian,
        String footerCopyright) {
    public static Branding defaults() {
        return new Branding("Lumira", "", "", "", false, "", false, "", "Lumira", 2026, "", "", "");
    }

    public void validate() {
        text(websiteName, 200, true);
        text(companyName, 200, false);
        text(footerIcp, 200, false);
        text(footerPoliceBeian, 200, false);
        text(footerCopyright, 1000, false);
        if (githubLinkEnabled == null
                || helpLinkEnabled == null
                || copyrightStartYear == null
                || copyrightStartYear < 1900
                || copyrightStartYear > 9999) throw invalid();
        for (var value :
                new String[] {
                    websiteFaviconUrl,
                    websiteLogoUrl,
                    loginBackgroundUrl,
                    githubLinkUrl,
                    helpLinkUrl
                }) url(value);
        for (var value : new String[] {websiteFaviconUrl, websiteLogoUrl, loginBackgroundUrl})
            if (value.startsWith(BrandingAssetReferences.PUBLIC_ASSET_PREFIX)
                    && BrandingAssetReferences.imageId(value) == null) throw invalid();
        if (githubLinkEnabled && githubLinkUrl.isBlank()
                || helpLinkEnabled && helpLinkUrl.isBlank()) throw invalid();
    }

    private static void text(String value, int limit, boolean required) {
        if (value == null || value.length() > limit || required && value.isBlank()) throw invalid();
    }

    private static void url(String value) {
        if (!SettingUrls.valid(value)) throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidBranding");
    }
}
