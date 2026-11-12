package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SettingHistoryReader {
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

    public SettingHistoryReader(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<HistoryItem> history(String section, int page, int pageSize) {
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

    @org.springframework.transaction.annotation.Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public HistoryDetail detail(String section, long version) {
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
}
