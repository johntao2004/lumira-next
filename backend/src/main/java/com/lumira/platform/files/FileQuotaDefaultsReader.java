package com.lumira.platform.files;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.SettingDocuments;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stored limits remain enforceable when optional file administration is uninstalled. */
@Service
public class FileQuotaDefaultsReader implements FileQuotaDefaultsPort {
    private final SettingDocuments documents;
    private final FileQuota fallback;

    public FileQuotaDefaultsReader(SettingDocuments documents, FileQuota fallback) {
        this.documents = documents;
        this.fallback = fallback;
    }

    @Override
    @Transactional(readOnly = true)
    public FileQuotaPolicyPort.Limits current() {
        var snapshot =
                documents.read(
                        "file-quota",
                        FileQuotaSettings.class,
                        () ->
                                new FileQuotaSettings(
                                        fallback.maximumBytes(), fallback.maximumFiles()));
        return new FileQuotaPolicyPort.Limits(
                snapshot.data().maximumBytes(), snapshot.data().maximumFiles());
    }
}
