package com.lumira.platform.settings;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings")
public class SettingHistoryController {
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

    private static final Set<String> SECTIONS =
            Set.of(
                    "branding",
                    "smtp",
                    "sms",
                    "profile-fields",
                    "agreements",
                    "watermark",
                    "maintenance",
                    "passkey",
                    "file-retention");

    private final SettingStore store;
    private final SettingRollbackService rollback;
    private final ObjectMapper mapper;

    public SettingHistoryController(
            SettingStore store, SettingRollbackService rollback, ObjectMapper mapper) {
        this.store = store;
        this.rollback = rollback;
        this.mapper = mapper;
    }

    @GetMapping("/{section}/history")
    public PageResult<HistoryItem> history(
            @PathVariable String section,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireSection(section);
        var request = new PageRequest(page, pageSize, "");
        var result = store.history(section, request);
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

    @GetMapping("/{section}/history/{version}")
    public HistoryDetail detail(@PathVariable String section, @PathVariable long version) {
        requireSection(section);
        if (version < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        var row =
                store.revision(section, version)
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

    @PostMapping("/{section}/rollback")
    public SettingRollbackService.View restore(
            @PathVariable String section,
            @BoundedBody(maxBytes = 16384, errorCode = "invalidSettingRevision") @RequestBody
                    SettingRollbackService.Input input,
            Authentication actor) {
        requireSection(section);
        return rollback.rollback(section, input, actor.getName());
    }

    private static void requireSection(String section) {
        if (!SECTIONS.contains(section))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
    }
}
