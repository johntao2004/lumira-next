package com.lumira.plugins.verificationdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.plugin.sdk.PluginResources;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VerificationDebuggerReceiverTest {
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void actualEnvironmentDisablesDebugInProductionEvenWhenExplicitlyRequested() {
        for (String key : List.of("lumira.sms.debug-enabled", "LUMIRA_SMS_DEBUG_ENABLED")) {
            var environment = new MockEnvironment().withProperty(key, "true");
            environment.setActiveProfiles("qa");
            assertTrue(VerificationDebuggerGate.from(environment).enabled());
            for (String profile : List.of("prod", "production", "PRODUCTION")) {
                environment.setActiveProfiles("qa", profile);
                assertFalse(VerificationDebuggerGate.from(environment).enabled());
            }
        }
        assertFalse(VerificationDebuggerGate.from(new MockEnvironment()).enabled());
    }

    @Test
    void onlyActivatesFromPostPublicationScheduleAndRejectsBrowserAndOversizedRequests()
            throws Exception {
        var resources = new TestResources();
        var maintenanceRuns = new AtomicInteger();
        var receiver =
                VerificationDebuggerReceiver.register(
                        resources,
                        VerificationDebuggerGate.forTest(true),
                        (path, key, authorization, body) ->
                                new VerificationDebuggerReceiver.HttpResponse(
                                        202, Map.of("path", path, "accepted", true)),
                        maintenanceRuns::incrementAndGet);

        assertFalse(receiver.ready());
        assertNull(receiver.baseEndpoint());
        assertNotNull(resources.scheduledTask);
        assertEquals(0, maintenanceRuns.get());
        assertTrue(resources.scheduleInterval.compareTo(Duration.ofMillis(10)) >= 0);

        resources.runScheduledTask();
        assertTrue(receiver.ready());
        assertEquals(1, maintenanceRuns.get());
        String endpoint = receiver.endpoint("send");
        assertNotNull(endpoint);

        var valid = post(endpoint, "{}", null).build();
        var accepted = client.send(valid, BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode());
        assertTrue(accepted.body().contains("\"accepted\":true"));

        var browserRequest = post(endpoint, "{}", "http://localhost").build();
        assertEquals(403, client.send(browserRequest, BodyHandlers.ofString()).statusCode());

        var queryRequest =
                HttpRequest.newBuilder(URI.create(endpoint + "?override=1"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
        assertEquals(404, client.send(queryRequest, BodyHandlers.ofString()).statusCode());

        var getRequest = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();
        assertEquals(405, client.send(getRequest, BodyHandlers.ofString()).statusCode());

        String oversized = "x".repeat(VerificationDebuggerReceiver.MAX_BODY_BYTES + 1);
        var largeRequest = post(endpoint, oversized, null).build();
        assertEquals(413, client.send(largeRequest, BodyHandlers.ofString()).statusCode());

        resources.close();
        assertFalse(receiver.ready());
        assertNull(receiver.baseEndpoint());
        assertNull(resources.scheduledTask);
        assertThrows(IOException.class, () -> client.send(valid, BodyHandlers.ofString()));
    }

    @Test
    void disabledAndProductionGatesNeverBindTheReceiver() throws Exception {
        for (var gate :
                List.of(
                        VerificationDebuggerGate.forTest(false),
                        VerificationDebuggerGate.forTest(true, "prod"),
                        VerificationDebuggerGate.forTest(true, "production"))) {
            var resources = new TestResources();
            var receiver =
                    VerificationDebuggerReceiver.register(
                            resources,
                            gate,
                            (path, key, authorization, body) ->
                                    new VerificationDebuggerReceiver.HttpResponse(202, Map.of()));

            assertFalse(receiver.ready());
            resources.runScheduledTask();
            assertFalse(receiver.ready());
            assertNull(receiver.baseEndpoint());
            resources.close();
        }
    }

    private static HttpRequest.Builder post(String endpoint, String body, String origin) {
        var request =
                HttpRequest.newBuilder(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        return request;
    }

    private static final class TestResources implements PluginResources, AutoCloseable {
        private final List<AutoCloseable> owned = new ArrayList<>();
        private Runnable scheduledTask;
        private Duration scheduleInterval;

        @Override
        public AutoCloseable schedule(String name, Duration interval, Runnable task) {
            this.scheduleInterval = interval;
            this.scheduledTask = task;
            AutoCloseable registration = () -> scheduledTask = null;
            owned.add(registration);
            return registration;
        }

        @Override
        public <T extends AutoCloseable> T own(String name, T resource) {
            owned.add(resource);
            return resource;
        }

        private void runScheduledTask() {
            if (scheduledTask != null) scheduledTask.run();
        }

        @Override
        public void close() throws Exception {
            for (int index = owned.size() - 1; index >= 0; index--) owned.get(index).close();
        }
    }
}
