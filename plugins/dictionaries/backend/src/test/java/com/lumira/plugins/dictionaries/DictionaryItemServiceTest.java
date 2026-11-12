package com.lumira.plugins.dictionaries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.dictionaries.api.DictionaryItemUsagePort;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DictionaryItemServiceTest {
    private static final UUID DICTIONARY_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final String ACTOR = "dictionary-admin";
    private static final String STATUS = "work_order_feedback_status";
    private static final String PRIORITY = "work_order_feedback_priority";
    private static final String CATEGORY = "work_order_feedback_category";
    private static final String DEFAULT = "work_order_feedback_default";

    private DictionaryItemRepository items;
    private DictionaryRepository dictionaries;
    private AdministrationPort guard;
    private AuditPort audit;
    private DictionaryLookupPort lookup;
    private DictionaryItemUsagePort feedbackUsage;
    private DictionaryItemUsagePort sensitiveUsage;
    private DictionaryItemService service;
    private List<DictionaryLookupPort.PolicyItem> statuses;
    private List<DictionaryLookupPort.PolicyItem> priorities;
    private List<DictionaryLookupPort.PolicyItem> defaults;
    private final AtomicBoolean feedbackInUse = new AtomicBoolean();
    private final AtomicBoolean sensitiveInUse = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        items = mock(DictionaryItemRepository.class);
        dictionaries = mock(DictionaryRepository.class);
        guard = mock(AdministrationPort.class);
        audit = mock(AuditPort.class);
        lookup = mock(DictionaryLookupPort.class);
        feedbackUsage =
                (code, value) -> code.startsWith("work_order_feedback_") && feedbackInUse.get();
        sensitiveUsage =
                (code, value) -> code.equals("sensitive_word_category") && sensitiveInUse.get();
        service =
                new DictionaryItemService(
                        items,
                        dictionaries,
                        guard,
                        audit,
                        lookup,
                        List.of(feedbackUsage, sensitiveUsage));
        statuses =
                new ArrayList<>(
                        List.of(
                                policyItem(STATUS, "OPEN", "待处理", "", 10),
                                policyItem(STATUS, "PROCESSING", "处理中", "", 20),
                                policyItem(STATUS, "RESOLVED", "已解决", "TERMINAL", 30),
                                policyItem(STATUS, "CLOSED", "已关闭", "TERMINAL", 40)));
        priorities =
                new ArrayList<>(
                        List.of(
                                policyItem(PRIORITY, "LOW", "低", "", 10),
                                policyItem(PRIORITY, "NORMAL", "普通", "", 20),
                                policyItem(PRIORITY, "HIGH", "高", "", 30),
                                policyItem(PRIORITY, "URGENT", "紧急", "", 40)));
        defaults =
                new ArrayList<>(
                        List.of(
                                policyItem(DEFAULT, "INITIAL_STATUS", "OPEN", "", 10),
                                policyItem(DEFAULT, "DEFAULT_PRIORITY", "NORMAL", "", 20)));
        when(lookup.policyItems(anyCollection()))
                .thenAnswer(
                        invocation -> {
                            Collection<String> codes = invocation.getArgument(0);
                            if (codes.contains(STATUS)) return List.copyOf(statuses);
                            if (codes.contains(PRIORITY)) return List.copyOf(priorities);
                            if (codes.contains(DEFAULT)) return List.copyOf(defaults);
                            return List.of();
                        });
        feedbackInUse.set(false);
        sensitiveInUse.set(false);
    }

    @Test
    void fixedStatusValuesAndTerminalMeaningCannotBeChanged() {
        setDictionary(STATUS);
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.create(
                                DICTIONARY_ID, input("WAITING", "Waiting", 10, true, ""), ACTOR));

        var open = item("OPEN", "待处理", 10, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(open);
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("PROCESSING", "处理中", 20, true, "", 1),
                                ACTOR));

        var resolved = item("RESOLVED", "已解决", 30, true, "TERMINAL", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(resolved);
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("RESOLVED", "已完成", 31, true, "", 1),
                                ACTOR));
        verify(items, never()).save(any(), any(), any(), anyBoolean());
    }

    @Test
    void everyFixedStatusRemainsEnabledAndCannotBeDeletedEvenWhenUnused() {
        setDictionary(STATUS);
        var open = item("OPEN", "待处理", 10, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(open);

        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("OPEN", "待处理", 10, false, "", 1),
                                ACTOR));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () -> service.delete(DICTIONARY_ID, ITEM_ID, 1, ACTOR));
        verify(items, never()).save(any(), any(), any(), anyBoolean());
        verify(items, never()).delete(any(), any());
    }

    @Test
    void unknownExistingFeedbackStatusCannotBeUpdatedAsIfItWereFixed() {
        setDictionary(STATUS);
        var legacy = item("WAITING", "Waiting", 10, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(legacy);

        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("WAITING", "Waiting", 10, true, "", 1),
                                ACTOR));
        verify(items, never()).save(any(), any(), any(), anyBoolean());
    }

    @Test
    void statusLabelAndOrderCanChangeWithoutChangingItsStableCode() {
        setDictionary(STATUS);
        var current = item("OPEN", "待处理", 10, true, "", 1);
        var updated = item("OPEN", "Needs review", 15, true, "", 2);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(current, updated);

        assertEquals(
                updated,
                service.update(
                        DICTIONARY_ID,
                        ITEM_ID,
                        input("OPEN", "Needs review", 15, true, "", 1),
                        ACTOR));
        verify(items)
                .save(
                        DICTIONARY_ID,
                        ITEM_ID,
                        input("OPEN", "Needs review", 15, true, "", 1),
                        false);
    }

    @Test
    void referencedItemsCannotBeDisabledOrDeleted() {
        setDictionary(CATEGORY);
        var category = item("QUESTION", "Question", 10, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(category);
        feedbackInUse.set(true);
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("QUESTION", "Question", 10, false, "", 1),
                                ACTOR));

        setDictionary("sensitive_word_category");
        var sensitive = item("POLICY", "Policy", 10, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(sensitive);
        sensitiveInUse.set(true);
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("OTHER", "Policy", 10, true, "", 1),
                                ACTOR));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () -> service.delete(DICTIONARY_ID, ITEM_ID, 1, ACTOR));
        verify(items, never()).save(any(), any(), any(), anyBoolean());
        verify(items, never()).delete(any(), any());
    }

    @Test
    void sensitiveCategorySupportsUnicodeAndSpacesUpToSixtyFourCodePoints() {
        setDictionary("sensitive_word_category");
        String category = "风险类别 " + "词".repeat(59);
        var created = item(category, "风险类别", 10, true, "", 1);
        when(items.find(eq(DICTIONARY_ID), any(UUID.class))).thenReturn(created);

        assertEquals(
                created,
                service.create(DICTIONARY_ID, input(category, "风险类别", 10, true, ""), ACTOR));
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.create(
                                DICTIONARY_ID, input(category + "词", "风险类别", 10, true, ""), ACTOR));
    }

    @Test
    void defaultKeysStayFixedAndInitialStatusMustBeEnabledAndNonterminal() {
        setDictionary(DEFAULT);
        var initial = item("INITIAL_STATUS", "OPEN", 10, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(initial);

        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("INITIAL_STATUS", "RESOLVED", 10, true, "", 1),
                                ACTOR));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("INITIAL_STATUS", "OPEN", 10, false, "", 1),
                                ACTOR));

        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("INITIAL_STATUS", "MISSING", 10, true, "", 1),
                                ACTOR));

        statuses.add(policyItem(STATUS, "RESOLVED", "Done", "TERMINAL", 30));
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("INITIAL_STATUS", "RESOLVED", 10, true, "", 1),
                                ACTOR));
        verify(items, never()).delete(any(), any());
    }

    @Test
    void defaultKeysCannotBeCreatedAndExistingDefaultsCannotBeDeleted() {
        setDictionary(DEFAULT);
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.create(
                                DICTIONARY_ID,
                                input("CUSTOM_DEFAULT", "CUSTOM", 90, true, ""),
                                ACTOR));
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.create(
                                DICTIONARY_ID,
                                input("INITIAL_STATUS", "OPEN", 10, true, ""),
                                ACTOR));

        var initial = item("INITIAL_STATUS", "OPEN", 10, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(initial);
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () -> service.delete(DICTIONARY_ID, ITEM_ID, 1, ACTOR));
        verify(items, never()).save(any(), any(), any(), anyBoolean());
        verify(items, never()).delete(any(), any());
    }

    @Test
    void defaultPriorityMustResolveToAnEnabledPriority() {
        setDictionary(DEFAULT);
        var defaultPriority = item("DEFAULT_PRIORITY", "NORMAL", 20, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(defaultPriority);
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("DEFAULT_PRIORITY", "MISSING", 20, true, "", 1),
                                ACTOR));

        priorities.clear();
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("DEFAULT_PRIORITY", "NORMAL", 20, true, "", 1),
                                ACTOR));
    }

    @Test
    void priorityExtensionsRequireStableUppercaseCodesAndCannotRenameExistingValues() {
        setDictionary(PRIORITY);
        var created = item("P0", "Critical", 0, true, "", 1);
        when(items.find(eq(DICTIONARY_ID), any(UUID.class))).thenReturn(created);
        assertEquals(
                created,
                service.create(DICTIONARY_ID, input("P0", "Critical", 0, true, ""), ACTOR));
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionaryItem",
                () ->
                        service.create(
                                DICTIONARY_ID, input("p1", "Critical plus", 1, true, ""), ACTOR));

        var current = item("NORMAL", "普通", 20, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(current);
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("STANDARD", "标准", 20, true, "", 1),
                                ACTOR));
    }

    @Test
    void configuredDefaultPriorityCannotBeDisabledOrDeleted() {
        setDictionary(PRIORITY);
        var current = item("NORMAL", "普通", 20, true, "", 1);
        when(items.find(DICTIONARY_ID, ITEM_ID)).thenReturn(current);
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () ->
                        service.update(
                                DICTIONARY_ID,
                                ITEM_ID,
                                input("NORMAL", "普通", 20, false, "", 1),
                                ACTOR));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryItemInUse",
                () -> service.delete(DICTIONARY_ID, ITEM_ID, 1, ACTOR));
        verify(items, never()).delete(any(), any());
    }

    private void setDictionary(String code) {
        when(dictionaries.find(DICTIONARY_ID))
                .thenReturn(
                        new DictionaryRepository.Dictionary(
                                DICTIONARY_ID, code, code, true, "FLAT", "", 1));
    }

    private static DictionaryItemRepository.Item item(
            String value,
            String label,
            int sortOrder,
            boolean enabled,
            String remark,
            long version) {
        return new DictionaryItemRepository.Item(
                ITEM_ID, DICTIONARY_ID, null, value, label, sortOrder, enabled, remark, version);
    }

    private static DictionaryItemService.Input input(
            String value, String label, int sortOrder, boolean enabled, String remark) {
        return new DictionaryItemService.Input(
                null, value, label, sortOrder, enabled, remark, null);
    }

    private static DictionaryItemService.Input input(
            String value,
            String label,
            int sortOrder,
            boolean enabled,
            String remark,
            long version) {
        return new DictionaryItemService.Input(
                null, value, label, sortOrder, enabled, remark, version);
    }

    private static DictionaryLookupPort.PolicyItem policyItem(
            String code, String value, String label, String remark, int sortOrder) {
        return new DictionaryLookupPort.PolicyItem(code, value, label, remark, sortOrder);
    }

    private static void assertReason(HttpStatus status, String reason, Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(status.value(), error.getStatusCode().value());
        assertEquals(reason, error.getReason());
    }
}
