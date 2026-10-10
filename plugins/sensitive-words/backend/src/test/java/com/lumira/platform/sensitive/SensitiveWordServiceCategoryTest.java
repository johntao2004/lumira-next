package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SensitiveWordServiceCategoryTest {
    private SensitiveWordRepository repository;
    private DictionaryLookupPort dictionaries;
    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        repository = mock(SensitiveWordRepository.class);
        dictionaries = mock(DictionaryLookupPort.class);
        service =
                new SensitiveWordService(
                        repository,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        moduleId -> true,
                        dictionaries);
    }

    @Test
    void exposesOnlyEnabledDictionaryChoices() {
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(List.of(option("DEFAULT", "默认分类", 10), option("IMPORTED", "导入词", 20)));

        assertEquals(
                List.of(
                        new SensitiveWordService.CategoryOption("DEFAULT", "默认分类", 10),
                        new SensitiveWordService.CategoryOption("IMPORTED", "导入词", 20)),
                service.categories("admin"));
    }

    @Test
    void createRequiresAnEnabledCategoryAndStoresTheDictionaryValue() {
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(
                        List.of(
                                option("DEFAULT", "默认分类", 10),
                                option("BUSINESS_RISK", "业务风险", 30)));
        var saved = word(UUID.randomUUID(), "保密信息", "BUSINESS_RISK", 1L);
        when(repository.exists("保密信息", null)).thenReturn(false);
        when(repository.find(any(UUID.class))).thenReturn(saved);

        service.create(
                new SensitiveWordService.Input(
                        "保密信息", "BUSINESS_RISK", "MEDIUM", "LOG_ONLY", true, null),
                "admin");

        verify(repository)
                .create(
                        any(UUID.class),
                        eq("保密信息"),
                        eq("保密信息"),
                        eq("BUSINESS_RISK"),
                        eq("MEDIUM"),
                        eq("LOG_ONLY"),
                        eq(true),
                        eq("admin"));
    }

    @Test
    void createRejectsMissingOrDisabledCategoriesBeforeLookingUpWords() {
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(List.of(option("DEFAULT", "默认分类", 10)));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.create(
                                        new SensitiveWordService.Input(
                                                "规则", "DISABLED", "MEDIUM", "LOG_ONLY", true, null),
                                        "admin"));

        assertEquals("invalidSensitiveWord", error.getReason());
        verify(repository, never()).exists(anyString(), any());
    }

    @Test
    void updateAllowsUnchangedDisabledLegacyCategoryButRejectsChangingToIt() {
        var id = UUID.randomUUID();
        var current = word(id, "旧规则", "LEGACY_DISABLED", 4L);
        var updated = word(id, "新规则", "LEGACY_DISABLED", 5L);
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(List.of(option("DEFAULT", "默认分类", 10), option("IMPORTED", "导入词", 20)));
        when(repository.find(id)).thenReturn(current, updated);
        when(repository.exists("新规则", id)).thenReturn(false);
        when(repository.update(
                        id,
                        4L,
                        "新规则",
                        "新规则",
                        "LEGACY_DISABLED",
                        "MEDIUM",
                        "LOG_ONLY",
                        true,
                        "admin"))
                .thenReturn(1);

        service.update(
                id,
                new SensitiveWordService.Input(
                        "新规则", "LEGACY_DISABLED", "MEDIUM", "LOG_ONLY", true, 4L),
                "admin");

        when(repository.find(id)).thenReturn(current);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.update(
                                        id,
                                        new SensitiveWordService.Input(
                                                "新规则",
                                                "LEGACY_DISABLED ",
                                                "MEDIUM",
                                                "LOG_ONLY",
                                                true,
                                                4L),
                                        "admin"));
        assertEquals("invalidSensitiveWord", error.getReason());
        // The successful unchanged-legacy update is the only repository update;
        // the rejected category change must not issue a second write.
        verify(repository)
                .update(
                        id,
                        4L,
                        "新规则",
                        "新规则",
                        "LEGACY_DISABLED",
                        "MEDIUM",
                        "LOG_ONLY",
                        true,
                        "admin");
    }

    @Test
    void importsUseImportedDefaultAndRejectDisabledCategories() {
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(List.of(option("DEFAULT", "默认分类", 10), option("IMPORTED", "导入词", 20)));
        when(repository.insertIfAbsent(
                        any(UUID.class),
                        eq("示例规则"),
                        eq("示例规则"),
                        eq("IMPORTED"),
                        eq("MEDIUM"),
                        eq("LOG_ONLY"),
                        eq("admin")))
                .thenReturn(1);

        service.importWords(
                new SensitiveWordService.ImportRequest(List.of("示例规则"), null, "MEDIUM", "LOG_ONLY"),
                "admin");

        verify(repository)
                .insertIfAbsent(
                        any(UUID.class),
                        eq("示例规则"),
                        eq("示例规则"),
                        eq("IMPORTED"),
                        eq("MEDIUM"),
                        eq("LOG_ONLY"),
                        eq("admin"));
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.importWords(
                                        new SensitiveWordService.ImportRequest(
                                                List.of("其他规则"), "DISABLED", "MEDIUM", "LOG_ONLY"),
                                        "admin"));
        assertEquals("invalidSensitiveWord", error.getReason());
    }

    @Test
    void categoryFilterRequiresAnEnabledValueAndUsesAnExactStoredCategory() {
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(List.of(option("RISK", "风险", 10), option("RISK_HIGH", "高风险", 20)));
        when(repository.list(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(repository.count(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        service.list(new PageRequest(1, 10, ""), null, "RISK", null, null, null, "admin");
        verify(repository).list(any(), eq(null), eq("RISK"), eq(null), eq(null), eq(null));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.list(
                                        new PageRequest(1, 10, ""),
                                        null,
                                        "LEGACY_DISABLED",
                                        null,
                                        null,
                                        null,
                                        "admin"));
        assertEquals("invalidSensitiveWord", error.getReason());
    }

    @Test
    void supplementaryUnicodeCategoryLimitUsesCodePointsForCreateAndFilter() {
        String category = "𐐷".repeat(33);
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(List.of(option(category, "Unicode category", 10)));
        when(repository.exists("规则", null)).thenReturn(false);
        var saved = word(UUID.randomUUID(), "规则", category, 1L);
        when(repository.find(any(UUID.class))).thenReturn(saved);

        service.create(
                new SensitiveWordService.Input("规则", category, "MEDIUM", "LOG_ONLY", true, null),
                "admin");

        verify(repository)
                .create(
                        any(UUID.class),
                        eq("规则"),
                        eq("规则"),
                        eq(category),
                        eq("MEDIUM"),
                        eq("LOG_ONLY"),
                        eq(true),
                        eq("admin"));

        when(repository.list(any(), any(), any(), any(), any(), any())).thenReturn(List.of(saved));
        when(repository.count(any(), any(), any(), any(), any(), any())).thenReturn(1L);
        service.list(new PageRequest(1, 10, ""), null, category, null, null, null, "admin");
        verify(repository).list(any(), eq(null), eq(category), eq(null), eq(null), eq(null));
    }

    @Test
    void sensitiveCategoryRejectsMoreThan64UnicodeCodePoints() {
        String category = "𐐷".repeat(65);
        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.create(
                                        new SensitiveWordService.Input(
                                                "规则", category, "MEDIUM", "LOG_ONLY", true, null),
                                        "admin"));

        assertEquals("invalidSensitiveWord", error.getReason());
        verify(repository, never()).exists(anyString(), any());
    }

    private static DictionaryLookupPort.PolicyItem option(String value, String label, int order) {
        return new DictionaryLookupPort.PolicyItem(
                SensitiveWordService.CATEGORY_DICTIONARY, value, label, "", order);
    }

    private static SensitiveWordRepository.Word word(
            UUID id, String word, String category, long version) {
        var now = OffsetDateTime.parse("2026-09-28T00:00:00Z");
        return new SensitiveWordRepository.Word(
                id,
                word,
                word,
                category,
                "MEDIUM",
                "LOG_ONLY",
                true,
                version,
                "admin",
                "admin",
                now,
                now);
    }
}
