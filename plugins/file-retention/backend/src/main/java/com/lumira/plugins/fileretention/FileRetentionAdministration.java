package com.lumira.plugins.fileretention;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.FileRetentionQueuePort;
import com.lumira.platform.common.FileRetentionSettings;
import com.lumira.platform.common.FileRetentionSettings.Mode;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.settings.SettingStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FileRetentionAdministration {
    public record View(long version, FileRetentionSettings data) {}

    public record Input(long version, FileRetentionSettings data, String changeReason) {}

    public record RollbackInput(long version, long sourceVersion, String changeReason) {}

    private final SettingStore settings;
    private final FileRetentionQueuePort files;
    private final AuditPort audit;
    private final AdministrationPort guard;
    private final ObjectMapper mapper;

    public FileRetentionAdministration(
            SettingStore settings,
            FileRetentionQueuePort files,
            AuditPort audit,
            AdministrationPort guard,
            ObjectMapper mapper) {
        this.settings = settings;
        this.files = files;
        this.audit = audit;
        this.guard = guard;
        this.mapper = mapper;
    }

    public View read(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.FILE_RETENTION);
        return readUnchecked();
    }

    private View readUnchecked() {
        return settings.find("file-retention")
                .map(
                        snapshot ->
                                new View(
                                        snapshot.version(),
                                        mapper.readValue(
                                                snapshot.payload(), FileRetentionSettings.class)))
                .orElseGet(() -> new View(0, FileRetentionSettings.defaults()));
    }

    @Transactional
    public View save(Input input, String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_RETENTION_UPDATE);
        if (input == null || input.data() == null || input.version() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFileRetention");
        }
        input.data().validate();
        var saved =
                settings.save(
                        "file-retention",
                        mapper.writeValueAsString(input.data()),
                        actor,
                        input.version(),
                        input.changeReason());
        if (input.data().mode() == Mode.AUTOMATIC) {
            enqueueExpired(input.data().retentionDays(), actor);
        }
        return new View(saved.version(), input.data());
    }

    public FileRetentionSettings current() {
        return readUnchecked().data();
    }

    @Transactional
    public View rollback(RollbackInput input, String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_RETENTION_UPDATE);
        if (input == null || input.version() < 0 || input.sourceVersion() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        }
        var revision =
                settings.revision("file-retention", input.sourceVersion())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        FileRetentionSettings data =
                mapper.readValue(revision.payload(), FileRetentionSettings.class);
        data.validate();
        var saved =
                settings.save(
                        "file-retention",
                        mapper.writeValueAsString(data),
                        actor,
                        input.version(),
                        input.changeReason(),
                        input.sourceVersion());
        applyAutomaticRetention(data, actor);
        return new View(saved.version(), data);
    }

    public int applyAutomaticRetention(FileRetentionSettings policy, String actor) {
        if (policy.mode() != Mode.AUTOMATIC) return 0;
        return enqueueExpired(policy.retentionDays(), actor);
    }

    private int enqueueExpired(int retentionDays, String actor) {
        var expired = files.expireTrashed(retentionDays);
        for (UUID id : expired) audit.append(actor, "file.retentionExpired", id);
        return expired.size();
    }
}
