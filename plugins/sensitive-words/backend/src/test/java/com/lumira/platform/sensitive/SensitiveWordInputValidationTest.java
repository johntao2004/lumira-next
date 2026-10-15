package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

class SensitiveWordInputValidationTest {
    @Test
    void rejectsEmptyOrExpandedMatchKeysBeforeCreateAndUpdateWrites() {
        for (String word : List.of("\u200b\u200c", "\u0130".repeat(128))) {
            var repository = mock(SensitiveWordRepository.class);
            var dictionaries = mock(DictionaryLookupPort.class);
            when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                    .thenReturn(
                            List.of(
                                    new DictionaryLookupPort.PolicyItem(
                                            SensitiveWordService.CATEGORY_DICTIONARY,
                                            "DEFAULT",
                                            "Default",
                                            "",
                                            0)));
            var service =
                    new SensitiveWordService(
                            repository,
                            mock(AdministrationPort.class),
                            mock(AuditPort.class),
                            moduleId -> true,
                            dictionaries);
            var input =
                    new SensitiveWordService.Input(word, "DEFAULT", "MEDIUM", "LOG_ONLY", true, 1L);
            var createError =
                    assertThrows(
                            ResponseStatusException.class, () -> service.create(input, "admin"));
            assertEquals(HttpStatus.BAD_REQUEST, createError.getStatusCode());
            assertEquals("invalidSensitiveWord", createError.getReason());
            verifyNoInteractions(repository);

            var id = UUID.randomUUID();
            when(repository.find(id))
                    .thenReturn(
                            new SensitiveWordRepository.Word(
                                    id,
                                    "valid",
                                    "valid",
                                    "DEFAULT",
                                    "MEDIUM",
                                    "LOG_ONLY",
                                    true,
                                    1L,
                                    "admin",
                                    "admin",
                                    null,
                                    null));
            var updateError =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> service.update(id, input, "admin"));
            assertEquals("invalidSensitiveWord", updateError.getReason());
            verify(repository, never()).exists(anyString(), any());
        }
    }

    @Test
    void allowsFormatCharactersInsideAnOtherwiseMeaningfulWord() {
        var repository = mock(SensitiveWordRepository.class);
        var dictionaries = mock(DictionaryLookupPort.class);
        when(dictionaries.policyItems(List.of(SensitiveWordService.CATEGORY_DICTIONARY)))
                .thenReturn(
                        List.of(
                                new DictionaryLookupPort.PolicyItem(
                                        SensitiveWordService.CATEGORY_DICTIONARY,
                                        "DEFAULT",
                                        "Default",
                                        "",
                                        0)));
        var service =
                new SensitiveWordService(
                        repository,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        moduleId -> true,
                        dictionaries);
        service.create(
                new SensitiveWordService.Input(
                        "a\u200bb", "DEFAULT", "MEDIUM", "LOG_ONLY", true, null),
                "admin");
        verify(repository)
                .create(
                        any(UUID.class),
                        eq("a\u200bb"),
                        eq("ab"),
                        eq("DEFAULT"),
                        eq("MEDIUM"),
                        eq("LOG_ONLY"),
                        eq(true),
                        eq("admin"));
    }
}
