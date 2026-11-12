package com.lumira.plugins.dictionaries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DictionaryServiceTest {
    private static final UUID DICTIONARY_ID = UUID.randomUUID();
    private static final String ACTOR = "dictionary-admin";
    private static final List<String> POLICY_CODES =
            List.of(
                    "work_order_feedback_status",
                    "work_order_feedback_priority",
                    "work_order_feedback_default",
                    "work_order_feedback_category",
                    "sensitive_word_category");

    private DictionaryRepository repository;
    private AdministrationPort guard;
    private AuditPort audit;
    private DictionaryService service;
    private DictionaryService inlineService;

    @BeforeEach
    void setUp() {
        repository = mock(DictionaryRepository.class);
        guard = mock(AdministrationPort.class);
        audit = mock(AuditPort.class);
        service = new DictionaryService(repository, guard, audit);
        inlineService = new DictionaryService(repository, guard, audit, true);
    }

    @Test
    void listAndFindRequireTheGlobalDictionariesPage() {
        service.list(new PageRequest(1, 10, ""), ACTOR);
        service.find(DICTIONARY_ID, ACTOR);

        verify(guard, times(2)).requirePageGlobal(ACTOR, PlatformPageId.DICTIONARIES);
        verify(guard, never()).requirePage(ACTOR, PlatformPageId.DICTIONARIES);
    }

    @Test
    void importCreationWorksWithImportGrantWithoutCreateGrant() {
        var imported = dictionary("imported_dictionary", true, "FLAT", 1);
        when(repository.find(any(UUID.class))).thenReturn(imported);
        var importGuard =
                globalGuard(
                        Set.of(
                                PlatformPageId.DICTIONARIES.accessPermission(),
                                PlatformOperation.DICTIONARY_IMPORT.permission()));
        var importService = new DictionaryService(repository, importGuard, audit);

        var result =
                importService.createFromImport(
                        new DictionaryService.Input(
                                "imported_dictionary", "Imported", true, "FLAT", "", null),
                        ACTOR);

        assertEquals(imported, result);
        verify(repository)
                .create(
                        any(UUID.class),
                        eq("imported_dictionary"),
                        eq("Imported"),
                        eq(true),
                        eq("FLAT"),
                        eq(""));
        verify(audit, never()).append(any(), any(), any());
    }

    @Test
    void createGrantAloneDoesNotAuthorizeImportCreation() {
        var createOnlyGuard =
                globalGuard(
                        Set.of(
                                PlatformPageId.DICTIONARIES.accessPermission(),
                                PlatformOperation.DICTIONARY_CREATE.permission()));
        var createOnlyService = new DictionaryService(repository, createOnlyGuard, audit);

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                createOnlyService.createFromImport(
                                        new DictionaryService.Input(
                                                "imported_dictionary",
                                                "Imported",
                                                true,
                                                "FLAT",
                                                "",
                                                null),
                                        ACTOR));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(repository, audit);
    }

    @Test
    void fixedPolicyDictionariesCannotBeDisabledOrChangedAwayFromFlat() {
        for (String code : POLICY_CODES) {
            reset(repository);
            when(repository.findForUpdate(DICTIONARY_ID))
                    .thenReturn(dictionary(code, true, "FLAT", 1));
            assertReason(
                    HttpStatus.CONFLICT,
                    "dictionaryInUse",
                    () -> service.update(DICTIONARY_ID, input("Name", false, "FLAT", 1), ACTOR));
            assertReason(
                    HttpStatus.CONFLICT,
                    "dictionaryInUse",
                    () -> service.update(DICTIONARY_ID, input("Name", true, "TREE", 1), ACTOR));
        }
        verify(repository, never()).update(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void fixedPolicyDictionariesCannotBeDeleted() {
        for (String code : POLICY_CODES) {
            reset(repository);
            when(repository.findForUpdate(DICTIONARY_ID))
                    .thenReturn(dictionary(code, true, "FLAT", 1));
            assertReason(
                    HttpStatus.CONFLICT,
                    "dictionaryInUse",
                    () -> service.delete(DICTIONARY_ID, 1, ACTOR));
        }
        verify(repository, never()).delete(any());
    }

    @Test
    void fixedPolicyDictionariesStillAllowNameAndRemarkEdits() {
        var old = dictionary("work_order_feedback_status", true, "FLAT", 1);
        var updated =
                new DictionaryRepository.Dictionary(
                        DICTIONARY_ID,
                        old.code(),
                        "Service status",
                        true,
                        "FLAT",
                        "Managed labels",
                        2);
        when(repository.findForUpdate(DICTIONARY_ID)).thenReturn(old);
        when(repository.find(DICTIONARY_ID)).thenReturn(updated);

        assertEquals(
                updated,
                service.update(
                        DICTIONARY_ID,
                        input("Service status", true, "FLAT", 1, "Managed labels"),
                        ACTOR));
        verify(repository).update(DICTIONARY_ID, "Service status", true, "FLAT", "Managed labels");
    }

    @Test
    void fixedPolicyDictionaryCodesCannotBeCreatedThroughTheAdminApi() {
        for (String code : POLICY_CODES) {
            assertReason(
                    HttpStatus.BAD_REQUEST,
                    "invalidDictionary",
                    () ->
                            service.create(
                                    new DictionaryService.Input(
                                            code, "Policy", true, "FLAT", "", null),
                                    ACTOR));
        }
        verify(repository, never()).create(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void updateOptimizedStateRejectsNestedItemsWhenFlat() {
        var old = dictionary("custom_tree", true, "TREE", 1);
        when(repository.findForUpdateWithNested(DICTIONARY_ID))
                .thenReturn(new DictionaryRepository.DictionaryUpdateState(old, true));

        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryFlat",
                () -> service.update(DICTIONARY_ID, input("Custom Flat", true, "FLAT", 1), ACTOR));
        verify(repository, never()).update(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void deleteOptimizedStateProtectsReferencesAndItems() {
        var old = dictionary("custom_item", true, "FLAT", 1);
        when(repository.findForDelete(DICTIONARY_ID))
                .thenReturn(new DictionaryRepository.DictionaryDeleteState(old, true, false));

        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryReferenced",
                () -> service.delete(DICTIONARY_ID, 1, ACTOR));

        reset(repository);
        when(repository.findForDelete(DICTIONARY_ID))
                .thenReturn(new DictionaryRepository.DictionaryDeleteState(old, false, true));

        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryInUse",
                () -> service.delete(DICTIONARY_ID, 1, ACTOR));
        verify(repository, never()).delete(any());
    }

    @Test
    void listUsesRepositoryListPageDirectly() {
        var req = new PageRequest(1, 10, "");
        var expected =
                new com.lumira.platform.common.PageResult<>(
                        List.of(dictionary("direct", true, "FLAT", 1)), 1, 1, 10);
        when(repository.listPage(req)).thenReturn(expected);

        var result = service.list(req);

        assertSame(expected, result);
        verify(repository, never()).list(any());
        verify(repository, never()).count(any());
    }

    @Test
    void createAndAuditReturnsImmediatelyWithoutSeparateAuditAppend() {
        var created = dictionary("custom_code", true, "FLAT", 1);
        when(repository.createAndAudit(
                        any(UUID.class),
                        eq("custom_code"),
                        eq("Custom"),
                        eq(true),
                        eq("FLAT"),
                        eq(""),
                        any(UUID.class),
                        eq(ACTOR)))
                .thenReturn(created);

        var result =
                inlineService.create(
                        new DictionaryService.Input(
                                "custom_code", "Custom", true, "FLAT", "", null),
                        ACTOR);

        assertEquals(created, result);
        verify(audit, never()).append(any(), any(), any());
    }

    @Test
    void createFallsBackToLegacyCreateAndAuditWhenSingleRoundTripUnstubbed() {
        var created = dictionary("custom_fallback", true, "FLAT", 1);
        when(repository.create(
                        any(UUID.class),
                        eq("custom_fallback"),
                        eq("Custom Fallback"),
                        eq(true),
                        eq("FLAT"),
                        eq("")))
                .thenReturn(created);

        var result =
                inlineService.create(
                        new DictionaryService.Input(
                                "custom_fallback", "Custom Fallback", true, "FLAT", "", null),
                        ACTOR);

        assertEquals(created, result);
        verify(audit).append(ACTOR, "dictionary.create", created.id());
    }

    @Test
    void customAuditPortAlwaysReceivesJavaAppendWithoutInlineAuditCte() {
        var created = dictionary("custom_hook", true, "FLAT", 1);
        when(repository.create(
                        any(UUID.class),
                        eq("custom_hook"),
                        eq("Custom Hook"),
                        eq(true),
                        eq("FLAT"),
                        eq("")))
                .thenReturn(created);

        var result =
                service.create(
                        new DictionaryService.Input(
                                "custom_hook", "Custom Hook", true, "FLAT", "", null),
                        ACTOR);

        assertEquals(created, result);
        verify(repository, never())
                .createAndAudit(any(), any(), any(), anyBoolean(), any(), any(), any(), any());
        verify(audit).append(ACTOR, "dictionary.create", created.id());
    }

    @Test
    void updateCheckedAndAuditSuccessReturnsUpdatedDirectly() {
        var updated = dictionary("custom_code", true, "FLAT", 2);
        when(repository.updateCheckedAndAudit(
                        eq(DICTIONARY_ID),
                        eq(1L),
                        eq(true),
                        eq("New Name"),
                        eq(true),
                        eq("FLAT"),
                        eq(""),
                        any(),
                        any(UUID.class),
                        eq(ACTOR)))
                .thenReturn(
                        new DictionaryRepository.UpdateOutcome(
                                true, "custom_code", 1L, false, updated));

        var result =
                inlineService.update(DICTIONARY_ID, input("New Name", true, "FLAT", 1L), ACTOR);

        assertEquals(updated, result);
        verify(repository, never()).update(any(), any(), anyBoolean(), any(), any());
        verify(audit, never()).append(any(), any(), any());
    }

    @Test
    void updateCheckedAndAuditEnforcesPrecedenceOnFailure() {
        when(repository.updateCheckedAndAudit(
                        any(),
                        any(),
                        anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(new DictionaryRepository.UpdateOutcome(false, null, 0L, false, null));
        var error404 =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                inlineService.update(
                                        DICTIONARY_ID, input("Name", true, "FLAT", 1L), ACTOR));
        assertEquals(HttpStatus.NOT_FOUND, error404.getStatusCode());

        when(repository.updateCheckedAndAudit(
                        any(),
                        any(),
                        anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(
                        new DictionaryRepository.UpdateOutcome(
                                true, "work_order_feedback_status", 1L, true, null));

        assertReason(
                HttpStatus.CONFLICT,
                "staleRecord",
                () -> inlineService.update(DICTIONARY_ID, input("Name", true, "FLAT", 2L), ACTOR));
        assertReason(
                HttpStatus.BAD_REQUEST,
                "invalidDictionary",
                () -> inlineService.update(DICTIONARY_ID, input(" ", true, "FLAT", 1L), ACTOR));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryInUse",
                () -> inlineService.update(DICTIONARY_ID, input("Name", false, "FLAT", 1L), ACTOR));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryFlat",
                () -> inlineService.update(DICTIONARY_ID, input("Name", true, "FLAT", 1L), ACTOR));
    }

    @Test
    void deleteCheckedAndAuditSuccessReturnsDirectly() {
        when(repository.deleteCheckedAndAudit(
                        eq(DICTIONARY_ID), eq(1L), any(), any(UUID.class), eq(ACTOR)))
                .thenReturn(
                        new DictionaryRepository.DeleteOutcome(
                                true, "custom_code", 1L, false, false, true));

        inlineService.delete(DICTIONARY_ID, 1L, ACTOR);

        verify(repository, never()).delete(any());
        verify(audit, never()).append(any(), any(), any());
    }

    @Test
    void deleteCheckedAndAuditEnforcesPrecedenceOnFailure() {
        when(repository.deleteCheckedAndAudit(any(), anyLong(), any(), any(), any()))
                .thenReturn(
                        new DictionaryRepository.DeleteOutcome(
                                false, null, 0L, false, false, false));
        var error404 =
                assertThrows(
                        ResponseStatusException.class,
                        () -> inlineService.delete(DICTIONARY_ID, 1L, ACTOR));
        assertEquals(HttpStatus.NOT_FOUND, error404.getStatusCode());

        when(repository.deleteCheckedAndAudit(any(), anyLong(), any(), any(), any()))
                .thenReturn(
                        new DictionaryRepository.DeleteOutcome(
                                true, "work_order_feedback_status", 1L, true, true, false));

        assertReason(
                HttpStatus.CONFLICT,
                "staleRecord",
                () -> inlineService.delete(DICTIONARY_ID, 2L, ACTOR));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryInUse",
                () -> inlineService.delete(DICTIONARY_ID, 1L, ACTOR));

        when(repository.deleteCheckedAndAudit(any(), anyLong(), any(), any(), any()))
                .thenReturn(
                        new DictionaryRepository.DeleteOutcome(
                                true, "custom_code", 1L, true, true, false));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryReferenced",
                () -> inlineService.delete(DICTIONARY_ID, 1L, ACTOR));

        when(repository.deleteCheckedAndAudit(any(), anyLong(), any(), any(), any()))
                .thenReturn(
                        new DictionaryRepository.DeleteOutcome(
                                true, "custom_code", 1L, false, true, false));
        assertReason(
                HttpStatus.CONFLICT,
                "dictionaryInUse",
                () -> inlineService.delete(DICTIONARY_ID, 1L, ACTOR));
    }

    private static DictionaryRepository.Dictionary dictionary(
            String code, boolean enabled, String structure, long version) {
        return new DictionaryRepository.Dictionary(
                DICTIONARY_ID, code, code, enabled, structure, "", version);
    }

    private static DictionaryService.Input input(
            String name, boolean enabled, String structure, long version) {
        return input(name, enabled, structure, version, "");
    }

    private static DictionaryService.Input input(
            String name, boolean enabled, String structure, long version, String remark) {
        return new DictionaryService.Input(null, name, enabled, structure, remark, version);
    }

    private static void assertReason(HttpStatus status, String reason, Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(status.value(), error.getStatusCode().value());
        assertEquals(reason, error.getReason());
    }

    private static AdministrationPort globalGuard(Set<String> grants) {
        return new AdministrationPort() {
            @Override
            public Set<String> require(String actor, String permission) {
                if (!grants.contains(permission))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
                return grants;
            }

            @Override
            public void requireUser(String actor, UUID target, String permission) {
                if (!grants.contains(permission))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }

            @Override
            public void requireGlobal(String actor, String permission) {
                if (!grants.contains(permission))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }

            @Override
            public boolean hasGlobal(String actor, String permission) {
                return grants.contains(permission);
            }
        };
    }
}
