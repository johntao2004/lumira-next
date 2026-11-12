package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AiTextGenerationBridgeTest {
    @Test
    @SuppressWarnings("unchecked")
    void legacyCallsLeaseTheCurrentExactOwnerAndNeverRetainThePreviousProvider() {
        var plugins = mock(PluginServicesPort.class);
        ObjectProvider<PluginServicesPort> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(plugins);
        var first = mock(AiTextGenerationServicePort.class);
        var second = mock(AiTextGenerationServicePort.class);
        var id = UUID.randomUUID();
        var values = List.of(new AiTextGenerationPort.Provider(id, "fixture", "model"));
        when(first.available()).thenReturn(values);
        var messages = List.of(Map.of("role", "user", "content", "synthetic"));
        when(second.chat(id, "model", messages)).thenReturn("replacement");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        invocation -> {
                            assertEquals("ai", invocation.getArgument(0));
                            assertEquals(
                                    AiTextGenerationServicePort.class, invocation.getArgument(1));
                            java.util.function.Function<AiTextGenerationServicePort, Object> call =
                                    invocation.getArgument(2);
                            return call.apply(calls.getAndIncrement() == 0 ? first : second);
                        })
                .when(plugins)
                .service(eq("ai"), eq(AiTextGenerationServicePort.class), any());
        var bridge = new AiTextGenerationBridge(services);
        assertEquals(values, bridge.available());
        assertEquals("replacement", bridge.chat(id, "model", messages));
        verify(first).available();
        verify(second).chat(id, "model", messages);
        verifyNoMoreInteractions(first, second);
        verify(services, times(2)).getObject();
    }
}
