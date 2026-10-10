package com.lumira.platform.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalizationQueryBudgetTest {
    @Test
    void resolvingPaged128KeyCatalogLoadsFallbackMetadataOncePerRequest() {
        var repository = mock(LocalizationRepository.class);
        when(repository.allActive()).thenReturn(List.of());

        var locales = mock(LocalizationLocaleRepository.class);
        var target = language("fr", "zh-CN", "en");
        var chinese = language("zh-CN", "en", "en");
        var english = language("en", "en", "en");
        when(locales.find("fr")).thenReturn(Optional.of(target));
        when(locales.find("zh-CN")).thenReturn(Optional.of(chinese));
        when(locales.find("en")).thenReturn(Optional.of(english));

        var keys = new ArrayList<String>();
        for (int i = 0; i < 128; i++) keys.add("platform.key" + i);
        var builtins = mock(LocalizationBuiltinCatalog.class);
        when(builtins.sourceLocale()).thenReturn("en");
        when(builtins.keys()).thenReturn(Set.copyOf(keys));
        when(builtins.sourceText(anyString())).thenAnswer(call -> "Source " + call.getArgument(0));
        when(builtins.messages(anyString())).thenReturn(Map.of());

        var service =
                new LocalizationService(
                        repository,
                        locales,
                        builtins,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class));

        for (int page = 1; page <= 2; page++) {
            service.list(new PageRequest(page, 20, ""), "fr", null, "admin");
            long metadataReads =
                    mockingDetails(locales).getInvocations().stream()
                            .filter(invocation -> invocation.getMethod().getName().equals("find"))
                            .count();
            assertEquals(
                    page * 4L,
                    metadataReads,
                    "metadata reads must not scale with keys or page rows");
        }
        verify(repository, times(2)).allActive();
    }

    private static LocalizationLocaleRepository.Language language(
            String locale, String fallback, String source) {
        return new LocalizationLocaleRepository.Language(
                locale, locale, source, fallback, false, true, 1L, null, null);
    }
}
