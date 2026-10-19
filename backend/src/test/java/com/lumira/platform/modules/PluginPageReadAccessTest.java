package com.lumira.platform.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AiTextGenerationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import com.lumira.platform.localization.*;
import com.lumira.platform.sensitive.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class PluginPageReadAccessTest {
    private static final String ACTOR = "page-reader";
    private static final PageRequest PAGE = new PageRequest(1, 10, "");

    @Test
    void localizationPageReadsNeedOnlyGlobalAccessButWritesStillNeedOperations() {
        var guard = accessOnly(LocalizationService.ACCESS);
        var repository = mock(LocalizationRepository.class);
        var locales = mock(LocalizationLocaleRepository.class);
        var english =
                new LocalizationLocaleRepository.Language(
                        "en", "English", "en", "en", true, true, 1L, null, null);
        when(locales.find("en")).thenReturn(Optional.of(english));
        when(locales.listEnabled()).thenReturn(List.of(english));
        var builtins = mock(LocalizationBuiltinCatalog.class);
        when(builtins.sourceLocale()).thenReturn("en");
        var service =
                new LocalizationService(
                        repository, locales, builtins, guard, mock(AuditPort.class));
        var generation = mock(AiTextGenerationPort.class);
        var ai = new LocalizationAiService(generation, service, guard, new ObjectMapper());
        UUID id = UUID.randomUUID();

        assertEquals(0, service.list(PAGE, "en", null, ACTOR).total());
        service.find(id, ACTOR);
        service.locales(ACTOR);
        assertEquals(List.of(), ai.providers(ACTOR));
        verify(repository).find(id);
        verify(guard, never()).require(ACTOR, LocalizationService.READ);
        verify(guard, never()).requireGlobal(ACTOR, LocalizationService.READ);
        assertForbidden(() -> service.create(null, ACTOR));
        assertForbidden(() -> service.update(id, null, ACTOR));
        assertForbidden(() -> ai.translate(null, ACTOR));
    }

    @Test
    void sensitivePageReadsAndPreviewsNeedOnlyGlobalAccessButWritesStaySeparate() {
        var guard = accessOnly(SensitiveWordService.ACCESS);
        var words = mock(SensitiveWordRepository.class);
        when(words.matcherSnapshot(anyLong()))
                .thenReturn(new SensitiveWordRepository.MatcherSnapshot(1, List.of()));
        var audit = mock(AuditPort.class);
        var service =
                new SensitiveWordService(
                        words, guard, audit, module -> true, mock(DictionaryLookupPort.class));
        var policies = mock(RoleContentPolicyRepository.class);
        var policyService =
                new RoleContentPolicyService(
                        policies, words, service, guard, audit, module -> true);
        UUID id = UUID.randomUUID();

        assertEquals(0, service.list(PAGE, null, null, null, null, null, ACTOR).total());
        service.find(id, ACTOR);
        assertEquals(List.of(), service.categories(ACTOR));
        service.check("test", ACTOR);
        assertEquals(0, policyService.list(PAGE, ACTOR).total());
        assertEquals(0, policyService.hits(PAGE, ACTOR).total());
        policyService.preview(null, "test", ACTOR);
        verify(guard, never()).require(ACTOR, SensitiveWordService.READ);
        verify(guard, never()).requireGlobal(ACTOR, SensitiveWordService.READ);
        assertForbidden(() -> service.create(null, ACTOR));
        assertForbidden(() -> service.update(id, null, ACTOR));
        assertForbidden(() -> service.delete(id, 1, ACTOR));
        assertForbidden(() -> service.importWords(null, ACTOR));
        assertForbidden(() -> policyService.save(id, null, ACTOR));
        verifyNoInteractions(audit);
    }

    @Test
    void scopedPageAccessCannotReadGlobalPluginData() {
        var guard = accessOnly(SensitiveWordService.ACCESS);
        doThrow(forbidden()).when(guard).requireGlobal(ACTOR, SensitiveWordService.ACCESS);
        var words = mock(SensitiveWordRepository.class);
        var service =
                new SensitiveWordService(
                        words,
                        guard,
                        mock(AuditPort.class),
                        module -> true,
                        mock(DictionaryLookupPort.class));
        assertForbidden(() -> service.find(UUID.randomUUID(), ACTOR));
        assertForbidden(() -> service.check("test", ACTOR));
        verifyNoInteractions(words);

        var localizationGuard = accessOnly(LocalizationService.ACCESS);
        doThrow(forbidden())
                .when(localizationGuard)
                .requireGlobal(ACTOR, LocalizationService.ACCESS);
        var entries = mock(LocalizationRepository.class);
        var localization =
                new LocalizationService(
                        entries,
                        mock(LocalizationLocaleRepository.class),
                        mock(LocalizationBuiltinCatalog.class),
                        localizationGuard,
                        mock(AuditPort.class));
        assertForbidden(() -> localization.find(UUID.randomUUID(), ACTOR));
        verifyNoInteractions(entries);
    }

    private static AdministrationPort accessOnly(String access) {
        var guard = mock(AdministrationPort.class);
        when(guard.require(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            if (!access.equals(invocation.getArgument(1))) throw forbidden();
                            return Set.of(access);
                        });
        doAnswer(
                        invocation -> {
                            if (!access.equals(invocation.getArgument(1))) throw forbidden();
                            return null;
                        })
                .when(guard)
                .requireGlobal(anyString(), anyString());
        return guard;
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    private static void assertForbidden(Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
