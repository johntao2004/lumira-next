package com.lumira.platform.dictionaries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.PluginServicesPort;
import com.lumira.platform.dictionaries.api.DictionaryReadServicePort;
import com.lumira.platform.identity.api.ProfileDictionaryPort.Selection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class DictionaryKernelAdapterTest {
    @Test
    void profilesWithoutDictionaryReferencesDoNotRequireAnInstalledProvider() {
        var services = mock(PluginServicesPort.class);
        var adapter = adapter(services);
        assertEquals(Map.of(), adapter.enabledOptionCounts(List.of()));
        assertEquals(Map.of(), adapter.enabledOptions(List.of(), 10));
        assertEquals(Set.of(), adapter.enabledSelections(List.of()));
        assertEquals(Set.of(), adapter.existingSelections(List.of()));
        assertEquals(Map.of(), adapter.selectionMetadata(List.of()));
        assertEquals(List.of(), adapter.policyItems(List.of()));
        assertFalse(adapter.isEnabled(null));
        assertFalse(adapter.isEnabled(""));
        verifyNoInteractions(services);
    }

    @Test
    void eachLookupUsesTheCurrentProviderAndPropagatesUnavailability() {
        var services = mock(PluginServicesPort.class);
        var first = mock(DictionaryReadServicePort.class);
        var second = mock(DictionaryReadServicePort.class);
        var selection = new Selection("choices", UUID.randomUUID());
        when(first.enabledSelections(List.of(selection))).thenReturn(Set.of(selection));
        when(second.enabledSelections(List.of(selection))).thenReturn(Set.of());
        var current = new java.util.concurrent.atomic.AtomicReference<>(first);
        when(services.service(eq("dictionaries"), eq(DictionaryReadServicePort.class), any()))
                .thenAnswer(
                        invocation -> {
                            if (current.get() == null)
                                throw new IllegalStateException("moduleUnavailable");
                            java.util.function.Function<DictionaryReadServicePort, ?> call =
                                    invocation.getArgument(2);
                            return call.apply(current.get());
                        });
        var adapter = adapter(services);
        assertEquals(Set.of(selection), adapter.enabledSelections(List.of(selection)));
        current.set(second);
        assertEquals(Set.of(), adapter.enabledSelections(List.of(selection)));
        current.set(null);
        assertEquals(
                "moduleUnavailable",
                assertThrows(
                                IllegalStateException.class,
                                () -> adapter.enabledSelections(List.of(selection)))
                        .getMessage());
    }

    private static DictionaryKernelAdapter adapter(PluginServicesPort services) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("plugins", services);
        return new DictionaryKernelAdapter(beans.getBeanProvider(PluginServicesPort.class));
    }
}
