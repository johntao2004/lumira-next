package com.lumira.plugins.fileretention;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.settings.SettingStore;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("file-retention")
@RequestMapping("/api/platform/settings/file-retention")
public class FileRetentionHistoryController {
    public record HistoryItem(
            long version,
            String changedBy,
            String changeReason,
            OffsetDateTime createdAt,
            Long sourceVersion) {}

    public record HistoryDetail(
            long version,
            Object data,
            String changedBy,
            String changeReason,
            OffsetDateTime createdAt,
            Long sourceVersion) {}

    private final SettingStore store;
    private final ObjectMapper mapper;
    private final AdministrationPort administration;

    public FileRetentionHistoryController(
            SettingStore store, ObjectMapper mapper, AdministrationPort administration) {
        this.store = store;
        this.mapper = mapper;
        this.administration = administration;
    }

    @GetMapping("/history")
    public PageResult<HistoryItem> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication actor) {
        administration.requirePageGlobal(actor.getName(), PlatformPageId.FILE_RETENTION);
        var request = new PageRequest(page, pageSize, "");
        var result = store.history("file-retention", request);
        return new PageResult<>(
                result.items().stream()
                        .map(
                                row ->
                                        new HistoryItem(
                                                row.version(),
                                                row.changedBy(),
                                                row.changeReason(),
                                                row.createdAt(),
                                                row.sourceVersion()))
                        .toList(),
                result.total(),
                result.page(),
                result.pageSize());
    }

    @GetMapping("/history/{version}")
    public HistoryDetail detail(@PathVariable long version, Authentication actor) {
        administration.requirePageGlobal(actor.getName(), PlatformPageId.FILE_RETENTION);
        if (version < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        var row =
                store.revision("file-retention", version)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        return new HistoryDetail(
                row.version(),
                mapper.readValue(row.payload(), Object.class),
                row.changedBy(),
                row.changeReason(),
                row.createdAt(),
                row.sourceVersion());
    }
}
