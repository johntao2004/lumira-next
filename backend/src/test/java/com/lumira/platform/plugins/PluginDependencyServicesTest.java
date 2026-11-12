package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.notifications.api.NotificationProducerPort;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

class PluginDependencyServicesTest {
    @Test
    void existingConsumerResolvesTheReplacementProviderInsteadOfKeepingOldBeans() throws Exception {
        try (var fixture = new Fixture();
                var replacement = new Fixture();
                var consumer = new AnnotationConfigApplicationContext()) {
            fixture.register(
                    consumer, List.of(new PluginManifest.Dependency("notifications", "1.0.0")));
            var proxy = consumer.getBeanFactory().getBean(NotificationProducerPort.class);
            fixture.handles.put("notifications", replacement.handle);
            proxy.publishFeedback(null, null, 1, "replacement", "OPEN", false);
            verify(replacement.service)
                    .publishFeedback(null, null, 1, "replacement", "OPEN", false);
            verify(fixture.service, never())
                    .publishFeedback(any(), any(), anyLong(), any(), any(), anyBoolean());
            assertEquals(0, ReflectionTestUtils.getField(replacement.handle, "calls"));
        }
    }

    @Test
    void onlyDeclaredDependenciesExposeTheirSharedContracts() throws Exception {
        try (var fixture = new Fixture();
                var consumer = new AnnotationConfigApplicationContext()) {
            fixture.register(consumer, List.of());
            assertTrue(
                    consumer.getBeanFactory()
                            .getBeansOfType(NotificationProducerPort.class)
                            .isEmpty());
            fixture.register(
                    consumer, List.of(new PluginManifest.Dependency("notifications", "1.0.0")));
            var proxy = consumer.getBeanFactory().getBean(NotificationProducerPort.class);
            assertNotSame(fixture.service, proxy);
            proxy.publishFeedback(null, null, 1, "title", "OPEN", false);
            verify(fixture.service).publishFeedback(null, null, 1, "title", "OPEN", false);
            assertEquals(0, ReflectionTestUtils.getField(fixture.handle, "calls"));
            ReflectionTestUtils.setField(fixture.handle, "accepting", false);
            assertThrows(
                    ResponseStatusException.class,
                    () -> proxy.publishFeedback(null, null, 2, "title", "OPEN", false));
            fixture.handles.clear();
            assertThrows(
                    ResponseStatusException.class,
                    () -> proxy.publishFeedback(null, null, 3, "title", "OPEN", false));
        }
    }

    @Test
    void dependencyLeaseRemainsHeldUntilTheCallingTransactionCompletes() throws Exception {
        try (var fixture = new Fixture();
                var consumer = new AnnotationConfigApplicationContext()) {
            fixture.register(
                    consumer, List.of(new PluginManifest.Dependency("notifications", "1.0.0")));
            var proxy = consumer.getBeanFactory().getBean(NotificationProducerPort.class);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                proxy.publishFeedback(null, null, 1, "title", "OPEN", false);
                assertEquals(1, ReflectionTestUtils.getField(fixture.handle, "calls"));
                for (var synchronization : TransactionSynchronizationManager.getSynchronizations())
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                assertEquals(0, ReflectionTestUtils.getField(fixture.handle, "calls"));
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
    }

    @Test
    void kernelCallsResolveOnlyTheRequestedCurrentOwnerAndReleaseFailedCalls() throws Exception {
        try (var fixture = new Fixture();
                var replacement = new Fixture()) {
            doReturn(true).when(fixture.runtime).isReadyForTraffic();
            assertEquals(
                    "notification-producer",
                    fixture.runtime.service(
                            "notifications",
                            NotificationProducerPort.class,
                            NotificationProducerPort::serviceType));
            fixture.handles.put("notifications", replacement.handle);
            fixture.runtime.service(
                    "notifications",
                    NotificationProducerPort.class,
                    service -> {
                        service.publishFeedback(null, null, 1, "kernel replacement", "OPEN", false);
                        return null;
                    });
            verify(replacement.service)
                    .publishFeedback(null, null, 1, "kernel replacement", "OPEN", false);
            var failure = new IllegalStateException("caller failure");
            assertSame(
                    failure,
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    fixture.runtime.service(
                                            "notifications",
                                            NotificationProducerPort.class,
                                            service -> {
                                                throw failure;
                                            })));
            assertEquals(0, ReflectionTestUtils.getField(replacement.handle, "calls"));
            ReflectionTestUtils.setField(replacement.handle, "accepting", false);
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            fixture.runtime.service(
                                    "notifications",
                                    NotificationProducerPort.class,
                                    service -> null));
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            fixture.runtime.service(
                                    "absent", NotificationProducerPort.class, service -> null));
        }
    }

    @Test
    void kernelCallsRemainLeasedThroughBothCommitAndRollbackEvenWhenCallerFails() throws Exception {
        for (int outcome :
                List.of(
                        TransactionSynchronization.STATUS_COMMITTED,
                        TransactionSynchronization.STATUS_ROLLED_BACK)) {
            try (var fixture = new Fixture()) {
                doReturn(true).when(fixture.runtime).isReadyForTraffic();
                TransactionSynchronizationManager.initSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try {
                    fixture.runtime.service(
                            "notifications",
                            NotificationProducerPort.class,
                            service -> service.serviceType());
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    fixture.runtime.service(
                                            "notifications",
                                            NotificationProducerPort.class,
                                            service -> {
                                                throw new IllegalArgumentException(
                                                        "rollback probe");
                                            }));
                    assertEquals(2, ReflectionTestUtils.getField(fixture.handle, "calls"));
                    for (var synchronization :
                            TransactionSynchronizationManager.getSynchronizations())
                        synchronization.afterCompletion(outcome);
                    assertEquals(0, ReflectionTestUtils.getField(fixture.handle, "calls"));
                } finally {
                    TransactionSynchronizationManager.clear();
                }
            }
        }
    }

    @Test
    void kernelCallsRejectUnreadyRuntimeAndGenericServiceMarker() throws Exception {
        try (var fixture = new Fixture()) {
            doReturn(false).when(fixture.runtime).isReadyForTraffic();
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            fixture.runtime.service(
                                    "notifications",
                                    NotificationProducerPort.class,
                                    service -> null));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            fixture.runtime.service(
                                    "notifications",
                                    com.lumira.platform.common.PluginServicePort.class,
                                    service -> null));
            doReturn(true).when(fixture.runtime).isReadyForTraffic();
            var states =
                    (com.lumira.platform.modules.ModuleStateStore)
                            ReflectionTestUtils.getField(fixture.runtime, "states");
            when(states.isEnabled("notifications")).thenReturn(false);
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            fixture.runtime.service(
                                    "notifications",
                                    NotificationProducerPort.class,
                                    service -> null));
            assertEquals(0, ReflectionTestUtils.getField(fixture.handle, "calls"));
        }
    }

    public static final class PortConsumer {
        final NotificationProducerPort port;

        public PortConsumer(NotificationProducerPort port) {
            this.port = port;
        }
    }

    @Test
    void declaredDependencyOverridesTheKernelCompatibilityAdapter() throws Exception {
        try (var fixture = new Fixture();
                var consumer = new AnnotationConfigApplicationContext()) {
            var compatibility = mock(NotificationProducerPort.class);
            fixture.host.registerBean(
                    "kernelCompatibility", NotificationProducerPort.class, () -> compatibility);
            fixture.host.refresh();
            consumer.setParent(fixture.host);
            fixture.register(
                    consumer, List.of(new PluginManifest.Dependency("notifications", "1.0.0")));
            consumer.registerBean(PortConsumer.class);
            consumer.refresh();
            var injected = consumer.getBean(PortConsumer.class).port;
            assertNotSame(compatibility, injected);
            injected.publishFeedback(null, null, 1, "declared dependency", "OPEN", false);
            verify(fixture.service)
                    .publishFeedback(null, null, 1, "declared dependency", "OPEN", false);
            verifyNoInteractions(compatibility);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final PluginRuntime runtime = mock(PluginRuntime.class, CALLS_REAL_METHODS);
        final AnnotationConfigApplicationContext host = new AnnotationConfigApplicationContext();
        final AnnotationConfigApplicationContext provider =
                new AnnotationConfigApplicationContext();
        final NotificationProducerPort service = mock(NotificationProducerPort.class);
        final Map<String, Object> handles = new HashMap<>();
        final Object handle;

        Fixture() throws Exception {
            when(service.ownerPluginId()).thenReturn("notifications");
            when(service.serviceType()).thenReturn("notification-producer");
            provider.registerBean(NotificationProducerPort.class, () -> service);
            provider.refresh();
            var constructor =
                    Class.forName(PluginRuntime.class.getName() + "$Handle")
                            .getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            handle = constructor.newInstance("notifications", provider, List.of(), null);
            handles.put("notifications", handle);
            ReflectionTestUtils.setField(runtime, "host", host);
            ReflectionTestUtils.setField(runtime, "handles", handles);
            var states = mock(com.lumira.platform.modules.ModuleStateStore.class);
            when(states.isEnabled(anyString())).thenReturn(true);
            ReflectionTestUtils.setField(runtime, "states", states);
        }

        void register(
                AnnotationConfigApplicationContext consumer,
                List<PluginManifest.Dependency> dependencies) {
            var manifest = mock(PluginManifest.class);
            when(manifest.dependencies()).thenReturn(dependencies);
            ReflectionTestUtils.invokeMethod(
                    runtime, "registerDependencyServices", manifest, consumer);
        }

        @Override
        public void close() {
            provider.close();
            host.close();
        }
    }
}
