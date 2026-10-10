package com.lumira.platform.settings;

import org.springframework.stereotype.Service;

@Service
public class BrandingReader {
    public record View(long version, Branding data) {}

    private final SettingDocuments documents;

    public BrandingReader(SettingDocuments documents) {
        this.documents = documents;
    }

    public View read() {
        var snapshot = documents.read("branding", Branding.class, Branding::defaults);
        return new View(snapshot.version(), snapshot.data());
    }
}
