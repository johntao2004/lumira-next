package com.lumira.plugins.verificationdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.plugin.sdk.PluginResources;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class VerificationDebuggerServiceTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void hostTransportProbeReachesRealLoopbackReceiverWithoutCreatingOtp() throws Exception {
        try (var resources = new TestResources()) {
            var service =
                    new VerificationDebuggerService(
                            resources,
                            new MockEnvironment().withProperty("LUMIRA_SMS_DEBUG_ENABLED", "true"),
                            allowAllGuard());
            resources.runScheduledTask();
            var session = service.start("qa");
            var request =
                    HttpRequest.newBuilder(URI.create(session.endpoint()))
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer " + session.bearerToken())
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(
                                                    Map.of(
                                                            "to",
                                                            "+15555550123",
                                                            "sender",
                                                            "LumiraDebug",
                                                            "message",
                                                            "Lumira local SMS transport test. No account code is included."))))
                            .build();
            var response = client.send(request, BodyHandlers.ofString());
            assertEquals(202, response.statusCode());
            assertEquals(true, mapper.readValue(response.body(), Map.class).get("accepted"));
            var message = service.messages("qa").items().getFirst();
            assertEquals("transport-test", message.provider());
            assertEquals("", message.code());
            assertEquals(0, service.messages("other").items().size());
        }
    }

    @Test
    void directProviderEndpointsAndPrivateInvokeReturnProviderShapesAndStoreCallerCode()
            throws Exception {
        var resources = new TestResources();
        var guard = allowAllGuard();
        var environment = new MockEnvironment().withProperty("LUMIRA_SMS_DEBUG_ENABLED", "true");
        var service = new VerificationDebuggerService(resources, environment, guard);
        var httpClient = resources.ownedResource(VerificationDebuggerHttpClient.class);

        assertEquals(false, httpClient.isTerminated());
        assertTrue(service.status("alice").enabled());
        assertEquals(false, service.status("alice").receiverReady());
        resources.runScheduledTask();
        assertTrue(service.status("alice").receiverReady());

        var session = service.start("alice");
        var aliyun = session.providers().aliyun();
        var invalidProvider =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.invoke(
                                        "alice",
                                        new VerificationDebuggerService.InvokeInput(
                                                "https://example.invalid/send",
                                                Map.of("to", "x"))));
        assertEquals(HttpStatus.BAD_REQUEST, invalidProvider.getStatusCode());
        var oversizedInvoke =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.invoke(
                                        "alice",
                                        new VerificationDebuggerService.InvokeInput(
                                                "aliyun", Map.of("payload", "x".repeat(5000)))));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, oversizedInvoke.getStatusCode());

        var aliyunSuccess =
                directPost(
                        aliyun.endpoint(),
                        aliyun.accessKeyId(),
                        aliyun.accessKeySecret(),
                        "{\"PhoneNumbers\":\"+15555550123\",\"SignName\":\"LumiraDebug\",\"TemplateCode\":\"SMS_DEBUG\",\"TemplateParam\":\"{\\\"code\\\":\\\"246810\\\"}\"}");
        assertEquals(200, aliyunSuccess.statusCode());
        Map<?, ?> aliyunJson = mapper.readValue(aliyunSuccess.body(), Map.class);
        assertEquals("OK", aliyunJson.get("Code"));
        assertEquals(4, aliyunJson.size());
        assertTrue(aliyunJson.containsKey("RequestId"));
        assertTrue(aliyunJson.containsKey("BizId"));
        assertEquals("246810", service.messages("alice").items().getFirst().code());
        assertEquals(0, service.messages("bob").items().size());

        var tencent = session.providers().tencent();
        var tencentSuccess =
                directPost(
                        tencent.endpoint(),
                        tencent.secretId(),
                        tencent.secretKey(),
                        mapper.writeValueAsString(
                                Map.of(
                                        "PhoneNumberSet", List.of("+15555550123"),
                                        "SmsSdkAppId", "1400000000",
                                        "TemplateId", "1234",
                                        "SignName", "LumiraDebug",
                                        "TemplateParamSet", List.of("135792"))));
        Map<?, ?> tencentJson = mapper.readValue(tencentSuccess.body(), Map.class);
        Map<?, ?> tencentPayload = assertInstanceOf(Map.class, tencentJson.get("Response"));
        List<?> tencentStatusSet =
                assertInstanceOf(List.class, tencentPayload.get("SendStatusSet"));
        assertEquals("Ok", assertInstanceOf(Map.class, tencentStatusSet.getFirst()).get("Code"));
        assertEquals("135792", service.messages("alice").items().get(1).code());

        var invoked =
                service.invoke(
                        "alice",
                        new VerificationDebuggerService.InvokeInput(
                                "tencent",
                                Map.of(
                                        "PhoneNumberSet", List.of("+15555550123"),
                                        "SmsSdkAppId", "1400000000",
                                        "TemplateId", "1234",
                                        "SignName", "LumiraDebug",
                                        "TemplateParamSet", List.of("864209"))));
        assertEquals(200, invoked.status());
        Map<?, ?> invokeBody = assertInstanceOf(Map.class, invoked.body());
        Map<?, ?> invokeResponse = assertInstanceOf(Map.class, invokeBody.get("Response"));
        List<?> statusSet = assertInstanceOf(List.class, invokeResponse.get("SendStatusSet"));
        Map<?, ?> status = assertInstanceOf(Map.class, statusSet.getFirst());
        assertEquals("Ok", status.get("Code"));
        assertEquals("864209", service.messages("alice").items().get(2).code());

        var invalidCredential =
                directPost(
                        aliyun.endpoint(),
                        aliyun.accessKeyId(),
                        "not-the-generated-secret",
                        "{\"PhoneNumbers\":\"+15555550123\",\"SignName\":\"LumiraDebug\",\"TemplateCode\":\"SMS_DEBUG\",\"TemplateParam\":\"{\\\"code\\\":\\\"999999\\\"}\"}");
        assertEquals(200, invalidCredential.statusCode());
        assertEquals(
                "InvalidAccessKeyId.NotFound",
                mapper.readValue(invalidCredential.body(), Map.class).get("Code"));
        assertEquals(3, service.messages("alice").items().size());

        var invalidParameters =
                directPost(
                        aliyun.endpoint(),
                        aliyun.accessKeyId(),
                        aliyun.accessKeySecret(),
                        "{\"PhoneNumbers\":\"+15555550124\",\"SignName\":\"LumiraDebug\",\"TemplateCode\":\"SMS_DEBUG\",\"TemplateParam\":\"{\\\"code\\\":\\\"999999\\\"}\"}");
        assertEquals(
                "isv.INVALID_PARAMETERS",
                mapper.readValue(invalidParameters.body(), Map.class).get("Code"));
        assertEquals(3, service.messages("alice").items().size());

        for (int index = 0; index < 2; index++) {
            var response =
                    directPost(
                            aliyun.endpoint(),
                            aliyun.accessKeyId(),
                            aliyun.accessKeySecret(),
                            aliyunBody("+15555550123", "12345" + index));
            assertEquals("OK", mapper.readValue(response.body(), Map.class).get("Code"));
        }
        var limited =
                directPost(
                        aliyun.endpoint(),
                        aliyun.accessKeyId(),
                        aliyun.accessKeySecret(),
                        "{\"PhoneNumbers\":\"+15555550123\",\"SignName\":\"LumiraDebug\",\"TemplateCode\":\"SMS_DEBUG\",\"TemplateParam\":\"{\\\"code\\\":\\\"987654\\\"}\"}");
        assertEquals(
                "isv.BUSINESS_LIMIT_CONTROL",
                mapper.readValue(limited.body(), Map.class).get("Code"));

        var authFixture =
                service.fixture(
                        "alice",
                        new VerificationDebuggerService.FixtureInput("tencent", "auth-failure"));
        assertTrue(authFixture.simulated());
        Map<?, ?> authFixtureResponse = assertInstanceOf(Map.class, authFixture.response().body());
        assertTrue(authFixtureResponse.containsKey("Response"));
        var timeoutFixture =
                service.fixture(
                        "alice", new VerificationDebuggerService.FixtureInput("aliyun", "timeout"));
        assertEquals(504, timeoutFixture.response().status());
        var successFixture =
                service.fixture(
                        "alice", new VerificationDebuggerService.FixtureInput("aliyun", "success"));
        assertEquals("135790", service.messages("alice").items().getLast().code());
        assertTrue(service.messages("alice").items().getLast().simulated());

        String closedEndpoint = aliyun.endpoint();
        resources.close();
        assertTrue(httpClient.isTerminated());
        assertEquals(
                List.of(
                        "verification-debugger-activation",
                        "verification-debugger-receiver",
                        "verification-debugger-sessions",
                        "verification-debugger-http-client"),
                resources.closedResourceNames);
        assertEquals(0, service.messages("alice").items().size());
        assertThrows(Exception.class, () -> directPost(closedEndpoint, "id", "secret", "{}"));
        assertThrows(ResponseStatusException.class, () -> service.start("alice"));
    }

    @Test
    void disabledGateDoesNotBindOrCreateSessions() throws Exception {
        var resources = new TestResources();
        var guard = allowAllGuard();
        var service = new VerificationDebuggerService(resources, new MockEnvironment(), guard);

        resources.runScheduledTask();
        assertEquals(false, service.status("alice").enabled());
        assertEquals(false, service.status("alice").receiverReady());
        var failure = assertThrows(ResponseStatusException.class, () -> service.start("alice"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        resources.close();
    }

    private HttpResponse directPost(String endpoint, String id, String secret, String body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .header("X-Lumira-Debug-Access-Key", id)
                        .header("Authorization", "Bearer " + secret)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        var response = client.send(request, BodyHandlers.ofString());
        return new HttpResponse(response.statusCode(), response.body());
    }

    private String aliyunBody(String recipient, String code) throws Exception {
        return mapper.writeValueAsString(
                Map.of(
                        "PhoneNumbers",
                        recipient,
                        "SignName",
                        "LumiraDebug",
                        "TemplateCode",
                        "SMS_DEBUG",
                        "TemplateParam",
                        mapper.writeValueAsString(Map.of("code", code))));
    }

    private static AdministrationPort allowAllGuard() {
        return new AdministrationPort() {
            @Override
            public Set<String> require(String actor, String permission) {
                return Set.of(permission);
            }

            @Override
            public void requireUser(String actor, java.util.UUID target, String permission) {}

            @Override
            public void requireGlobal(String actor, String permission) {}

            @Override
            public boolean hasGlobal(String actor, String permission) {
                return true;
            }
        };
    }

    private record HttpResponse(int statusCode, String body) {}

    private static final class TestResources implements PluginResources, AutoCloseable {
        private final List<OwnedResource> owned = new ArrayList<>();
        private final List<String> closedResourceNames = new ArrayList<>();
        private Runnable scheduledTask;

        @Override
        public AutoCloseable schedule(String name, Duration interval, Runnable task) {
            scheduledTask = task;
            AutoCloseable registration = () -> scheduledTask = null;
            owned.add(new OwnedResource(name, registration));
            return registration;
        }

        @Override
        public <T extends AutoCloseable> T own(String name, T resource) {
            owned.add(new OwnedResource(name, resource));
            return resource;
        }

        private <T extends AutoCloseable> T ownedResource(Class<T> type) {
            return owned.stream()
                    .map(OwnedResource::resource)
                    .filter(type::isInstance)
                    .map(type::cast)
                    .findFirst()
                    .orElseThrow();
        }

        private void runScheduledTask() {
            if (scheduledTask != null) scheduledTask.run();
        }

        @Override
        public void close() throws Exception {
            for (int index = owned.size() - 1; index >= 0; index--) {
                OwnedResource resource = owned.get(index);
                closedResourceNames.add(resource.name());
                resource.resource().close();
            }
        }

        private record OwnedResource(String name, AutoCloseable resource) {}
    }
}
