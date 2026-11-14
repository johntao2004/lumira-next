package com.lumira.plugins.dictionaries;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import java.util.*;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String HOST_AUDIT_REPOSITORY = "AuditRepository";
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
    private final boolean inlineAudit;

    @Autowired
    public DictionaryService(
            DictionaryRepository repository, AdministrationPort guard, AuditPort audit) {
        this(repository, guard, audit, isHostAuditRepository(audit));
    }

    DictionaryService(
            DictionaryRepository repository,
            AdministrationPort guard,
            AuditPort audit,
            boolean inlineAudit) {
        this.repository = repository;
        this.guard = guard;
        this.audit = audit;
        this.inlineAudit = inlineAudit;
    }

    private static boolean isHostAuditRepository(AuditPort audit) {
        return audit != null
                && HOST_AUDIT_REPOSITORY.equals(
                        AopProxyUtils.ultimateTargetClass(audit).getSimpleName());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DictionaryRepository.Dictionary> list(PageRequest p) {
        var pageResult = repository.listPage(p);
        if (pageResult != null) {
            return pageResult;
        }
        return new PageResult<>(
                repository.list(p) != null ? repository.list(p) : List.of(),
                repository.count(p),
                p.page(),
                p.pageSize());
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
        if (operation == PlatformOperation.DICTIONARY_IMPORT)
            guard.requireOperationGlobal(actor, operation);
        else guard.requireConcurrentOperationGlobal(actor, operation);
    }

    static boolean isValid(Input input) {
        return input != null
                && input.name() != null
                && !input.name().isBlank()
                && input.name().strip().length() <= 200
                && input.enabled() != null
                && input.structure() != null
                && Set.of("FLAT", "TREE").contains(input.structure())
                && (input.remark() == null || input.remark().length() <= 1000);
    }

    static void validate(Input input) {
        if (!isValid(input)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionary");
        }
    }

    private void validateCreateInput(Input input) {
        validate(input);
        if (input.code() == null || !input.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionary");
        }
        if (POLICY_DICTIONARIES.contains(input.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidDictionary");
        }
    }

    private DictionaryRepository.Dictionary insertRecord(UUID id, Input input) {
        var created =
                repository.create(
                        id,
                        input.code(),
                        input.name().strip(),
                        input.enabled(),
                        input.structure(),
                        input.remark() == null ? "" : input.remark());
        return created != null ? created : repository.find(id);
    }

    @Transactional
    public DictionaryRepository.Dictionary create(Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_CREATE);
        validateCreateInput(input);
        var id = UUID.randomUUID();
        var created =
                inlineAudit
                        ? repository.createAndAudit(
                                id,
                                input.code(),
                                input.name().strip(),
                                input.enabled(),
                                input.structure(),
                                input.remark() == null ? "" : input.remark(),
                                UUID.randomUUID(),
                                actor)
                        : null;
        if (created != null) {
            return created;
        }
        var result = insertRecord(id, input);
        audit.append(actor, "dictionary.create", result.id());
        return result;
    }

    @Transactional
    public DictionaryRepository.Dictionary createFromImport(Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_IMPORT);
        return createRecord(input);
    }

    private DictionaryRepository.Dictionary createRecord(Input input) {
        validateCreateInput(input);
        return insertRecord(UUID.randomUUID(), input);
    }

    private void checkUpdatePreconditions(
            String oldCode, long oldVersion, boolean hasNestedItems, Input input) {
        if (input.version() == null || input.version() != oldVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        }
        validate(input);
        if (POLICY_DICTIONARIES.contains(oldCode)
                && (!input.enabled() || !input.structure().equals("FLAT"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryInUse");
        }
        if (input.structure().equals("FLAT") && hasNestedItems) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryFlat");
        }
    }

    @Transactional
    public DictionaryRepository.Dictionary update(UUID id, Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_UPDATE);
        boolean valid = isValid(input);
        var outcome =
                inlineAudit
                        ? repository.updateCheckedAndAudit(
                                id,
                                input.version(),
                                valid,
                                valid ? input.name().strip() : "",
                                input.enabled(),
                                input.structure(),
                                input.remark() == null ? "" : input.remark(),
                                POLICY_DICTIONARIES,
                                UUID.randomUUID(),
                                actor)
                        : null;
        if (outcome != null) {
            if (!outcome.found()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            if (outcome.updated() != null) {
                return outcome.updated();
            }
            checkUpdatePreconditions(
                    outcome.oldCode(), outcome.oldVersion(), outcome.hasNestedItems(), input);
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        DictionaryRepository.Dictionary old;
        boolean hasNestedItems;
        var state = repository.findForUpdateWithNested(id);
        if (state != null) {
            old = state.dictionary();
            hasNestedItems = state.hasNestedItems();
        } else {
            old = repository.findForUpdate(id);
            hasNestedItems = repository.hasItems(id, true);
        }
        checkUpdatePreconditions(old.code(), old.version(), hasNestedItems, input);
        var updated =
                repository.update(
                        id,
                        input.name().strip(),
                        input.enabled(),
                        input.structure(),
                        input.remark() == null ? "" : input.remark());
        audit.append(actor, "dictionary.update", id);
        return updated != null ? updated : repository.find(id);
    }

    private void checkDeletePreconditions(
            String oldCode,
            long oldVersion,
            boolean hasProfileReferences,
            boolean hasItems,
            long version) {
        if (version != oldVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        }
        if (POLICY_DICTIONARIES.contains(oldCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryInUse");
        }
        if (hasProfileReferences) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryReferenced");
        }
        if (hasItems) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dictionaryInUse");
        }
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_DELETE);
        var outcome =
                inlineAudit
                        ? repository.deleteCheckedAndAudit(
                                id, version, POLICY_DICTIONARIES, UUID.randomUUID(), actor)
                        : null;
        if (outcome != null) {
            if (!outcome.found()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            if (outcome.deleted()) {
                return;
            }
            checkDeletePreconditions(
                    outcome.oldCode(),
                    outcome.oldVersion(),
                    outcome.hasProfileReferences(),
                    outcome.hasItems(),
                    version);
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        DictionaryRepository.Dictionary old;
        boolean hasProfileReferences;
        boolean hasItems;
        var state = repository.findForDelete(id);
        if (state != null) {
            old = state.dictionary();
            hasProfileReferences = state.hasProfileReferences();
            hasItems = state.hasItems();
        } else {
            old = repository.findForUpdate(id);
            hasProfileReferences = repository.hasProfileReferences(id);
            hasItems = repository.hasItems(id, false);
        }
        checkDeletePreconditions(
                old.code(), old.version(), hasProfileReferences, hasItems, version);
        repository.delete(id);
        audit.append(actor, "dictionary.delete", id);
    }
}
