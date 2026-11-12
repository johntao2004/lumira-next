package com.lumira.platform.identity;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileDictionarySelectionPort;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolve a current published field, never a caller-supplied dictionary code. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProfileDictionarySelectionService implements ProfileDictionarySelectionPort {
    private final ProfileSchemaPort schemas;
    private final ProfileDictionaryPort dictionaries;

    public ProfileDictionarySelectionService(
            ProfileSchemaPort schemas, ProfileDictionaryPort dictionaries) {
        this.schemas = schemas;
        this.dictionaries = dictionaries;
    }

    @Override
    public PageResult<ProfileDictionaryPort.Item> list(
            Host host, String fieldKey, PageRequest page) {
        return dictionaries.lookupItems(code(host, fieldKey), page);
    }

    @Override
    public ProfileDictionaryPort.Item find(Host host, String fieldKey, UUID itemId) {
        return dictionaries.lookupItem(code(host, fieldKey), itemId);
    }

    private String code(Host host, String fieldKey) {
        return schemas.read().data().forHost(host.page()).fields().stream()
                .filter(field -> field.key().equals(fieldKey))
                .filter(field -> Boolean.TRUE.equals(field.custom()))
                .filter(field -> "SELECT".equals(field.type()) && field.usesDictionary())
                .map(field -> field.dictionaryCode())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "notFound"));
    }
}
