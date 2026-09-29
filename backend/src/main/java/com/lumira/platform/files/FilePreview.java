package com.lumira.platform.files;

/** Plain-text preview response with an explicit truncation signal for large documents. */
public record FilePreview(String text, boolean truncated) {}
