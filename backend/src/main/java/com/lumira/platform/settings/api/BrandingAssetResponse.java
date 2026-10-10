package com.lumira.platform.settings.api;

/** Public route and normalized size returned after a branding image upload. */
public record BrandingAssetResponse(String url, long sizeBytes) {}
