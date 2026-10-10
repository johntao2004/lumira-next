package com.lumira.platform.settings;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Typed JSON documents; SettingStore retains authorization, concurrency and revision auditing. */
@Service
public class SettingDocuments {
    public record Snapshot<T>(long version, T data) {}

    private final SettingStore store;
    private final ObjectMapper mapper;

    public SettingDocuments(SettingStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public <T> Snapshot<T> read(String section, Class<T> type, Supplier<T> defaults) {
        return store.find(section)
                .map(
                        value ->
                                new Snapshot<>(
                                        value.version(), mapper.readValue(value.payload(), type)))
                .orElseGet(() -> new Snapshot<>(0, defaults.get()));
    }

    public <T> Snapshot<T> save(
            String section,
            long version,
            T data,
            String reason,
            String actor,
            String invalidCode,
            Consumer<T> validate) {
        if (data == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidCode);
        validate.accept(data);
        var saved = store.save(section, mapper.writeValueAsString(data), actor, version, reason);
        return new Snapshot<>(saved.version(), data);
    }
}
