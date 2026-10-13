package com.lumira.platform.dictionaries;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DictionaryService {
    public record Input(
            String code,
            String name,
            Boolean enabled,
            String structure,
            String remark,
            Long version) {}

    private static final Set<String> POLICY_DICTIONARIES =
            Set.of(
                    "work_order_feedback_status",
                    "work_order_feedback_priority",
                    "work_order_feedback_default",
                    "work_order_feedback_category",
                    "sensitive_word_category");
    private final DictionaryRepository repository;
    private final AdministrationPort guard;
    private final AuditPort audit;

    public DictionaryService(
            DictionaryRepository repository, AdministrationPort guard, AuditPort audit) {
        this.repository = repository;
        this.guard = guard;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DictionaryRepository.Dictionary> list(PageRequest p) {
        return new PageResult<>(repository.list(p), repository.count(p), p.page(), p.pageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DictionaryRepository.Dictionary> list(PageRequest p, String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.DICTIONARIES);
        return list(p);
    }

    @Transactional(readOnly = true)
    public DictionaryRepository.Dictionary find(UUID id) {
        return repository.find(id);
    }

    @Transactional(readOnly = true)
    public DictionaryRepository.Dictionary find(UUID id, String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.DICTIONARIES);
        return find(id);
    }

    private void begin(String actor, PlatformOperation operation) {
        guard.requireOperationGlobal(actor, operation);
    }

    static void validate(Input input) {
        if (input.name() == null
                || input.name().isBlank()
                || input.name().strip().length() > 200
                || input.enabled() == null
                || input.structure() == null
                || !Set.of("FLAT", "TREE").contains(input.structure())
                || (input.remark() != null && input.remark().length() > 1000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionary");
    }

    @Transactional
    public DictionaryRepository.Dictionary create(Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_CREATE);
        var created = createRecord(input);
        audit.append(actor, "dictionary.create", created.id());
        return created;
    }

    @Transactional
    public DictionaryRepository.Dictionary createFromImport(Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_IMPORT);
        return createRecord(input);
    }

    private DictionaryRepository.Dictionary createRecord(Input input) {
        validate(input);
        if (input.code() == null || !input.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionary");
        if (POLICY_DICTIONARIES.contains(input.code()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionary");
        var id = UUID.randomUUID();
        repository.create(
                id,
                input.code(),
                input.name().strip(),
                input.enabled(),
                input.structure(),
                input.remark() == null ? "" : input.remark());
        return repository.find(id);
    }

    @Transactional
    public DictionaryRepository.Dictionary update(UUID id, Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_UPDATE);
        var old = repository.find(id);
        if (input.version() == null || input.version() != old.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        validate(input);
        if (POLICY_DICTIONARIES.contains(old.code())
                && (!input.enabled() || !input.structure().equals("FLAT")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryInUse");
        if (input.structure().equals("FLAT") && repository.hasItems(id, true))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryFlat");
        repository.update(
                id,
                input.name().strip(),
                input.enabled(),
                input.structure(),
                input.remark() == null ? "" : input.remark());
        audit.append(actor, "dictionary.update", id);
        return repository.find(id);
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_DELETE);
        var old = repository.find(id);
        if (version != old.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        if (POLICY_DICTIONARIES.contains(old.code()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryInUse");
        if (repository.hasProfileReferences(id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryReferenced");
        if (repository.hasItems(id, false))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryInUse");
        repository.delete(id);
        audit.append(actor, "dictionary.delete", id);
    }
}
