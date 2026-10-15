package com.lumira.platform.files;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.FileQuotaDefaultsPort;
import com.lumira.platform.common.FileQuotaPolicyPort;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.settings.SettingDocuments;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileQuotaSettingsService implements FileQuotaDefaultsPort {
    public record View(long version, FileQuotaSettings data) {}

    private static final String SECTION = "file-quota";

    private final SettingDocuments documents;
    private final FileQuota fallback;
    private final AdministrationPort guard;

    public FileQuotaSettingsService(
            SettingDocuments documents, FileQuota fallback, AdministrationPort guard) {
        this.documents = documents;
        this.fallback = fallback;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public View read(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.FILES_ALL);
        return readUnchecked();
    }

    private View readUnchecked() {
        var snapshot =
                documents.read(
                        SECTION,
                        FileQuotaSettings.class,
                        () ->
                                new FileQuotaSettings(
                                        fallback.maximumBytes(), fallback.maximumFiles()));
        return new View(snapshot.version(), snapshot.data());
    }

    @Transactional
    public View save(long version, FileQuotaSettings data, String actor) {
        var saved =
                documents.save(
                        SECTION,
                        version,
                        data,
                        null,
                        actor,
                        "invalidFileQuota",
                        FileQuotaSettings::validate);
        return new View(saved.version(), saved.data());
    }

    @Override
    @Transactional(readOnly = true)
    public FileQuotaPolicyPort.Limits current() {
        FileQuotaSettings data = readUnchecked().data();
        return new FileQuotaPolicyPort.Limits(data.maximumBytes(), data.maximumFiles());
    }
}
