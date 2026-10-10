package com.lumira.platform.dictionaries;

import com.lumira.platform.common.AuditPort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DictionaryDatasetService {
    private final DictionaryService dictionaries;
    private final DictionaryItemRepository items;
    private final JdbcTemplate jdbc;
    private final AuditPort audit;

    public DictionaryDatasetService(
            DictionaryService dictionaries,
            DictionaryItemRepository items,
            JdbcTemplate jdbc,
            AuditPort audit) {
        this.dictionaries = dictionaries;
        this.items = items;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DictionaryDataset export(UUID id) {
        var type = dictionaries.find(id);
        long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_dictionary_item WHERE dictionary_id=?",
                        Long.class,
                        id);
        if (count > DictionaryDataset.MAX_ITEMS)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "dictionaryDatasetTooLarge");
        var rows = items.all(id);
        Map<UUID, String> values = new HashMap<>();
        for (var item : rows) values.put(item.id(), item.value());
        return new DictionaryDataset(
                1,
                type.code(),
                type.name(),
                type.enabled(),
                type.structure(),
                type.remark(),
                rows.stream()
                        .map(
                                item ->
                                        new DictionaryDataset.Entry(
                                                item.value(),
                                                item.label(),
                                                item.parentId() == null
                                                        ? null
                                                        : values.get(item.parentId()),
                                                item.sortOrder(),
                                                item.enabled(),
                                                item.remark()))
                        .toList());
    }

    public record Preview(String code, String name, String structure, int itemCount) {}

    @Transactional(readOnly = true)
    public Preview preview(DictionaryDataset dataset) {
        DictionaryService.validate(
                new DictionaryService.Input(
                        dataset.code(),
                        dataset.name(),
                        dataset.enabled(),
                        dataset.structure(),
                        dataset.remark(),
                        null));
        if (dataset.code() == null || !dataset.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionary");
        var ordered = dataset.validatedOrder();
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform_dictionary WHERE code=?)",
                        Boolean.class,
                        dataset.code())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "duplicateRecord");
        return new Preview(dataset.code(), dataset.name(), dataset.structure(), ordered.size());
    }

    @Transactional
    public DictionaryRepository.Dictionary importNew(DictionaryDataset dataset, String actor) {
        var ordered = dataset.validatedOrder();
        var type =
                dictionaries.create(
                        new DictionaryService.Input(
                                dataset.code(),
                                dataset.name(),
                                dataset.enabled(),
                                dataset.structure(),
                                dataset.remark(),
                                null),
                        actor);
        Map<String, UUID> ids = new HashMap<>();
        for (var item : ordered) ids.put(item.value(), UUID.randomUUID());
        jdbc.batchUpdate(
                "INSERT INTO platform_dictionary_item(id,dictionary_id,parent_id,value,label,sort_order,enabled,remark) VALUES(?,?,?,?,?,?,?,?)",
                ordered,
                500,
                (statement, item) -> {
                    statement.setObject(1, ids.get(item.value()));
                    statement.setObject(2, type.id());
                    statement.setObject(
                            3, item.parentValue() == null ? null : ids.get(item.parentValue()));
                    statement.setString(4, item.value());
                    statement.setString(5, item.label().strip());
                    statement.setInt(6, item.sortOrder());
                    statement.setBoolean(7, item.enabled());
                    statement.setString(8, item.remark() == null ? "" : item.remark());
                });
        audit.append(actor, "dictionary.import", type.id());
        return type;
    }
}
