package com.lumira.platform.files;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.settings.SettingStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FileRetentionPolicy {
    public enum Mode {
        AUTOMATIC,
        MANUAL
    }

    public record Data(Mode mode, int retentionDays) {
        public static Data defaults() {
            return new Data(Mode.AUTOMATIC, 30);
        }

        public void validate() {
            if (mode == null || retentionDays < 1 || retentionDays > 365) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFileRetention");
            }
        }
    }

    public record View(long version, Data data) {}

    public record Input(long version, Data data, String changeReason) {}

    public record RollbackInput(long version, long sourceVersion, String changeReason) {}

    private final SettingStore settings;
    private final FileRepository files;
    private final AuditPort audit;
    private final AdministrationPort guard;
    private final ObjectMapper mapper;

    public FileRetentionPolicy(
            SettingStore settings,
            FileRepository files,
            AuditPort audit,
            AdministrationPort guard,
            ObjectMapper mapper) {
        this.settings = settings;
        this.files = files;
        this.audit = audit;
        this.guard = guard;
        this.mapper = mapper;
    }

    public View read() {
        return settings.find("file-retention")
                .map(
                        snapshot ->
                                new View(
                                        snapshot.version(),
                                        mapper.readValue(snapshot.payload(), Data.class)))
                .orElseGet(() -> new View(0, Data.defaults()));
    }

    @Transactional
    public View save(Input input, String actor) {
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

    public Data current() {
        return read().data();
    }

    @Transactional
    public View rollback(RollbackInput input, String actor) {
        guard.require(actor, "platform:settings:write");
        guard.requireGlobal(actor, "platform:settings:write");
        if (input == null || input.version() < 0 || input.sourceVersion() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        }
        var revision =
                settings.revision("file-retention", input.sourceVersion())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        Data data = mapper.readValue(revision.payload(), Data.class);
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

    public int applyAutomaticRetention(Data policy, String actor) {
        if (policy.mode() != Mode.AUTOMATIC) return 0;
        return enqueueExpired(policy.retentionDays(), actor);
    }

    private int enqueueExpired(int retentionDays, String actor) {
        int total = 0;
        List<UUID> expired;
        do {
            expired = files.expireTrashed(retentionDays);
            for (UUID id : expired) audit.append(actor, "file.retentionExpired", id);
            total += expired.size();
        } while (expired.size() == 100);
        return total;
    }
}
