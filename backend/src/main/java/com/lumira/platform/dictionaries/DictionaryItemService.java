package com.lumira.platform.dictionaries;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.dictionaries.api.DictionaryItemUsagePort;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import java.util.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DictionaryItemService {
    public record Input(
            UUID parentId,
            String value,
            String label,
            Integer sortOrder,
            Boolean enabled,
            String remark,
            Long version) {}

    private static final String FEEDBACK_STATUS = "work_order_feedback_status";
    private static final String FEEDBACK_PRIORITY = "work_order_feedback_priority";
    private static final String FEEDBACK_CATEGORY = "work_order_feedback_category";
    private static final String FEEDBACK_DEFAULT = "work_order_feedback_default";
    private static final String SENSITIVE_WORD_CATEGORY = "sensitive_word_category";
    private static final Map<String, Boolean> FIXED_FEEDBACK_STATUSES =
            Map.of("OPEN", false, "PROCESSING", false, "RESOLVED", true, "CLOSED", true);
    private static final Set<String> FEEDBACK_DEFAULT_VALUES =
            Set.of(
                    "INITIAL_STATUS",
                    "DEFAULT_PRIORITY",
                    "UPLOAD_BUCKET",
                    "IMAGE_CATEGORY",
                    "IMAGE_REMARK");
    private static final Map<String, String> FIXED_UNUSED_DEFAULTS =
            Map.of(
                    "UPLOAD_BUCKET",
                    "support_feedback",
                    "IMAGE_CATEGORY",
                    "工单反馈",
                    "IMAGE_REMARK",
                    "工单反馈富文本图片");
    private static final String POLICY_CODE_PATTERN = "[A-Z][A-Z0-9_]{0,31}";
    private final DictionaryItemRepository items;
    private final DictionaryRepository dictionaries;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final DictionaryLookupPort lookup;
    private final List<DictionaryItemUsagePort> usagePorts;

    public DictionaryItemService(
            DictionaryItemRepository items,
            DictionaryRepository dictionaries,
            AdministrationPort guard,
            AuditPort audit,
            DictionaryLookupPort lookup,
            List<DictionaryItemUsagePort> usagePorts) {
        this.items = items;
        this.dictionaries = dictionaries;
        this.guard = guard;
        this.audit = audit;
        this.lookup = lookup;
        this.usagePorts = List.copyOf(usagePorts);
    }

    private void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }

    private void begin(String actor, PlatformOperation operation) {
        guard.requireOperationGlobal(actor, operation);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DictionaryItemRepository.Item> list(
            UUID dictionary, PageRequest p, String parentValue) {
        dictionaries.find(dictionary);
        var parent = ParentFilter.parse(parentValue);
        if (parent.id() != null) items.find(dictionary, parent.id());
        return new PageResult<>(
                items.list(dictionary, p, parent),
                items.count(dictionary, p, parent),
                p.page(),
                p.pageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DictionaryItemRepository.Item> list(
            UUID dictionary, PageRequest p, String parentValue, String actor) {
        guard.requirePage(actor, PlatformPageId.DICTIONARIES);
        return list(dictionary, p, parentValue);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public java.util.List<DictionaryItemRepository.Item> ancestors(UUID dictionary, UUID id) {
        items.find(dictionary, id);
        return items.ancestors(dictionary, id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public java.util.List<DictionaryItemRepository.Item> ancestors(
            UUID dictionary, UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.DICTIONARIES);
        return ancestors(dictionary, id);
    }

    @Transactional(readOnly = true)
    public DictionaryItemRepository.Item find(UUID dictionary, UUID id) {
        return items.find(dictionary, id);
    }

    @Transactional(readOnly = true)
    public DictionaryItemRepository.Item find(UUID dictionary, UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.DICTIONARIES);
        return find(dictionary, id);
    }

    private void validate(UUID dictionary, UUID id, Input in, boolean creating) {
        var type = dictionaries.find(dictionary);
        if (in.value() == null
                || in.value().isBlank()
                || in.value().strip().length() > 200
                || in.label() == null
                || in.label().isBlank()
                || in.label().strip().length() > 200
                || in.sortOrder() == null
                || in.enabled() == null
                || (in.remark() != null && in.remark().length() > 1000))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        String value = in.value().strip();
        if (type.code().equals(FEEDBACK_CATEGORY) && !value.matches(POLICY_CODE_PATTERN))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        if (type.code().equals(FEEDBACK_PRIORITY)
                && creating
                && !value.matches(POLICY_CODE_PATTERN))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        if (type.code().equals(SENSITIVE_WORD_CATEGORY)
                && value.codePointCount(0, value.length()) > 64)
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        if (type.code().equals(FEEDBACK_STATUS)
                && (!value.matches(POLICY_CODE_PATTERN)
                        || !FIXED_FEEDBACK_STATUSES.containsKey(value)))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        if (type.code().equals(FEEDBACK_DEFAULT) && !FEEDBACK_DEFAULT_VALUES.contains(value))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        if (in.parentId() != null) {
            if (type.structure().equals("FLAT")) fail(HttpStatus.CONFLICT, "dictionaryFlat");
            items.find(dictionary, in.parentId());
            if (in.parentId().equals(id) || items.cycles(dictionary, id, in.parentId()))
                fail(HttpStatus.CONFLICT, "dictionaryCycle");
        }
    }

    @Transactional
    public DictionaryItemRepository.Item create(UUID dictionary, Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_CREATE);
        var type = dictionaries.find(dictionary);
        var id = UUID.randomUUID();
        validate(dictionary, id, input, true);
        if (type.code().equals(FEEDBACK_DEFAULT))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        if (type.code().equals(FEEDBACK_STATUS)) {
            Boolean terminal = FIXED_FEEDBACK_STATUSES.get(input.value().strip());
            if (terminal == null || !input.enabled() || isTerminal(input.remark()) != terminal)
                fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        }
        items.save(dictionary, id, input, true);
        audit.append(actor, "dictionaryItem.create", id);
        return items.find(dictionary, id);
    }

    @Transactional
    public DictionaryItemRepository.Item update(
            UUID dictionary, UUID id, Input input, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_UPDATE);
        var old = items.find(dictionary, id);
        if (input == null || input.version() == null || input.version() != old.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        var type = dictionaries.find(dictionary);
        String code = type.code(),
                oldValue = old.value(),
                newValue = input.value() == null ? "" : input.value().strip();
        if (code.equals(FEEDBACK_STATUS) && !FIXED_FEEDBACK_STATUSES.containsKey(oldValue))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        if ((code.equals(FEEDBACK_CATEGORY)
                        || code.equals(FEEDBACK_STATUS)
                        || code.equals(FEEDBACK_PRIORITY)
                        || code.equals(FEEDBACK_DEFAULT))
                && !oldValue.equals(newValue)) fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
        if (code.equals(SENSITIVE_WORD_CATEGORY)
                && !oldValue.equals(newValue)
                && isProtectedReference(code, oldValue))
            fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
        validate(dictionary, id, input, false);
        if (code.equals(FEEDBACK_STATUS)) {
            Boolean terminal = FIXED_FEEDBACK_STATUSES.get(oldValue);
            if (terminal == null) fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
            if (!input.enabled()) fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
            if (isTerminal(input.remark()) != terminal)
                fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        }
        if (code.equals(FEEDBACK_DEFAULT)) validateDefaultUpdate(old, input);
        if (old.enabled() && !input.enabled() && isProtectedReference(code, oldValue))
            fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
        items.save(dictionary, id, input, false);
        audit.append(actor, "dictionaryItem.update", id);
        return items.find(dictionary, id);
    }

    @Transactional
    public void delete(UUID dictionary, UUID id, long version, String actor) {
        begin(actor, PlatformOperation.DICTIONARY_DELETE);
        var old = items.find(dictionary, id);
        if (old.version() != version) fail(HttpStatus.CONFLICT, "staleRecord");
        var type = dictionaries.find(dictionary);
        if (items.hasChildren(dictionary, id)) fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
        if (items.hasProfileSelections(id)) fail(HttpStatus.CONFLICT, "dictionaryItemReferenced");
        if (type.code().equals(FEEDBACK_DEFAULT)
                || type.code().equals(FEEDBACK_STATUS)
                || isProtectedReference(type.code(), old.value()))
            fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
        items.delete(dictionary, id);
        audit.append(actor, "dictionaryItem.delete", id);
    }

    private void validateDefaultUpdate(DictionaryItemRepository.Item old, Input input) {
        String key = old.value(), label = input.label().strip();
        if (!Boolean.TRUE.equals(input.enabled())) fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
        if (key.equals("INITIAL_STATUS") || key.equals("DEFAULT_PRIORITY"))
            validateDefaultValue(key, label, input.enabled());
        else if (!Objects.equals(old.label(), label))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
    }

    private void validateDefaultValue(String key, String label, Boolean enabled) {
        if (!Boolean.TRUE.equals(enabled)) fail(HttpStatus.CONFLICT, "dictionaryItemInUse");
        if (key.equals("INITIAL_STATUS")) {
            boolean valid =
                    lookup.policyItems(List.of(FEEDBACK_STATUS)).stream()
                            .anyMatch(
                                    item ->
                                            item.value().equalsIgnoreCase(label)
                                                    && !isTerminal(item.remark()));
            if (!valid) fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        } else if (key.equals("DEFAULT_PRIORITY")) {
            boolean valid =
                    lookup.policyItems(List.of(FEEDBACK_PRIORITY)).stream()
                            .anyMatch(item -> item.value().equalsIgnoreCase(label));
            if (!valid) fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
        } else if (!Objects.equals(FIXED_UNUSED_DEFAULTS.get(key), label))
            fail(HttpStatus.BAD_REQUEST, "invalidDictionaryItem");
    }

    private boolean isProtectedReference(String code, String value) {
        if (usagePorts.stream().anyMatch(port -> port.isInUse(code, value))) return true;
        if (code.equals(FEEDBACK_STATUS)) return defaultReferences("INITIAL_STATUS", value);
        if (code.equals(FEEDBACK_PRIORITY)) return defaultReferences("DEFAULT_PRIORITY", value);
        return false;
    }

    private boolean defaultReferences(String key, String value) {
        return lookup.policyItems(List.of(FEEDBACK_DEFAULT)).stream()
                .anyMatch(item -> item.value().equals(key) && item.label().equalsIgnoreCase(value));
    }

    private static boolean isTerminal(String remark) {
        return "TERMINAL".equalsIgnoreCase(remark == null ? "" : remark.strip());
    }
}
