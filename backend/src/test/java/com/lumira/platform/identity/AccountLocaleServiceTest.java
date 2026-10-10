package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.LocaleRegistryPort;
import com.lumira.platform.common.PluginExtensionsPort;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class AccountLocaleServiceTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PluginExtensionsPort> plugins = mock(ObjectProvider.class);

    private final AccountLocaleRepository repository = mock(AccountLocaleRepository.class);
    private final AccountLocaleService service = new AccountLocaleService(repository, plugins);

    @Test
    void rejectsInvalidAndUnregisteredTagsWithoutWriting() {
        for (String input :
                new String[] {null, "", "en_US", "<script>", "xx", "en-US", "a".repeat(64)}) {
            assertEquals(
                    400,
                    assertThrows(ResponseStatusException.class, () -> service.save("alice", input))
                            .getStatusCode()
                            .value());
        }
        verifyNoInteractions(repository);
    }

    @Test
    void normalizesAndScopesWritesToTheAuthenticatedAccount() {
        when(repository.save("alice", "zh-CN")).thenReturn(true);
        assertEquals("zh-CN", service.save("alice", "zh-cn").locale());
        verify(repository).save("alice", "zh-CN");
        assertEquals(
                401,
                assertThrows(ResponseStatusException.class, () -> service.save("disabled", "en"))
                        .getStatusCode()
                        .value());
    }

    @Test
    void followsThePluginRegistryIncludingCustomAndDisabledLanguages() {
        var runtime = mock(PluginExtensionsPort.class);
        when(plugins.getIfAvailable()).thenReturn(runtime);
        when(runtime.extensions(eq(LocaleRegistryPort.class), any())).thenReturn(List.of(true));
        when(repository.save("alice", "it")).thenReturn(true);
        assertEquals("it", service.save("alice", "it").locale());
        when(runtime.extensions(eq(LocaleRegistryPort.class), any())).thenReturn(List.of(false));
        when(repository.find("alice"))
                .thenReturn(Optional.of(new AccountLocaleRepository.Preference("it")));
        assertNull(service.read("alice").locale());
        assertThrows(ResponseStatusException.class, () -> service.save("alice", "en"));
        verify(repository, never()).save("alice", "en");
    }

    @Test
    void keepsUnsetPreferencesAndFallsBackToBuiltInsWithoutThePlugin() {
        var runtime = mock(PluginExtensionsPort.class);
        when(plugins.getIfAvailable()).thenReturn(runtime);
        when(runtime.extensions(eq(LocaleRegistryPort.class), any())).thenReturn(List.of());
        when(repository.find("alice"))
                .thenReturn(Optional.of(new AccountLocaleRepository.Preference(null)));
        assertNull(service.read("alice").locale());
        when(repository.save("alice", "en")).thenReturn(true);
        assertEquals("en", service.save("alice", "en").locale());
        verify(repository, never()).save(eq("alice"), isNull());
    }
}
