package com.lumira.platform.dictionaries;

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

    @BeforeEach
    void setUp() {
        repository = mock(DictionaryRepository.class);
        guard = mock(AdministrationPort.class);
        audit = mock(AuditPort.class);
        service = new DictionaryService(repository, guard, audit);
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
            when(repository.find(DICTIONARY_ID)).thenReturn(dictionary(code, true, "FLAT", 1));
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
            when(repository.find(DICTIONARY_ID)).thenReturn(dictionary(code, true, "FLAT", 1));
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
        when(repository.find(DICTIONARY_ID)).thenReturn(old, updated);

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
