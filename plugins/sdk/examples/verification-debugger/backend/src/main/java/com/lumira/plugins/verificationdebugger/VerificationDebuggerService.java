package com.lumira.plugins.verificationdebugger;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.plugin.sdk.PluginResources;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public final class VerificationDebuggerService {
    private static final String ACCESS = "platform:verification-debugger:access";
    private static final String READ = "platform:verification-debugger:read";
    private static final String CONTROL = "platform:verification-debugger:control";
    private static final String FIXTURE = "platform:verification-debugger:fixture";
    private static final int MAX_INVOKE_BODY_BYTES = 4 * 1024;

    public record Status(boolean enabled, boolean receiverReady, String allowedRecipient) {}

    public record WebhookConfiguration(
            String provider,
            boolean enabled,
            String market,
            String sender,
            String template,
            String endpoint,
            int timeoutSeconds) {}

    public record SessionResponse(
            UUID id,
            java.time.Instant expiresAt,
            String endpoint,
            String bearerToken,
            String recipient,
            WebhookConfiguration configuration,
            VerificationDebuggerSessions.ProviderCredentials providers) {
        @Override
        public String toString() {
            return "SessionResponse[id="
                    + id
                    + ", expiresAt="
                    + expiresAt
                    + ", credentials=redacted]";
        }
    }

    public record FixtureInput(String provider, String scenario) {}

    public record InvokeInput(String provider, Map<String, Object> body) {}

    public record InvokeResponse(int status, Object body) {}

    public record FixtureRequest(
            String method, String path, Map<String, String> headers, Object body) {}

    public record FixtureResponse(int status, Object body) {}

    public record FixtureResult(
            FixtureRequest request, FixtureResponse response, boolean simulated) {}

    private final VerificationDebuggerGate gate;
    private final VerificationDebuggerSessions sessions;
    private final VerificationDebuggerReceiver receiver;
    private final AdministrationPort guard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final VerificationDebuggerHttpClient httpClient = new VerificationDebuggerHttpClient();

    public VerificationDebuggerService(
            PluginResources resources, Environment environment, AdministrationPort guard) {
        this.gate = VerificationDebuggerGate.from(environment);
        this.guard = guard;
        resources.own("verification-debugger-http-client", httpClient);
        this.sessions = new VerificationDebuggerSessions();
        resources.own("verification-debugger-sessions", sessions);
        this.receiver =
                VerificationDebuggerReceiver.register(
                        resources, gate, this::handleLoopbackRequest, sessions::maintain);
    }

    public Status status(String actor) {
        require(actor, READ);
        return new Status(
                gate.enabled(), receiver.ready(), VerificationDebuggerSessions.ALLOWED_RECIPIENT);
    }

    public SessionResponse start(String actor) {
        require(actor, CONTROL);
        requireAvailable();
        var current = sessions.start(actor, receiver.baseEndpoint());
        return new SessionResponse(
                current.id(),
                current.expiresAt(),
                current.endpoint(),
                current.bearerToken(),
                current.recipient(),
                new WebhookConfiguration(
                        "webhook",
                        true,
                        "international",
                        VerificationDebuggerSessions.DEBUG_SENDER,
                        VerificationDebuggerSessions.MESSAGE_TEMPLATE,
                        current.endpoint(),
                        5),
                current.providers());
    }

    public VerificationDebuggerSessions.MessagePage messages(String actor) {
        require(actor, READ);
        return sessions.messages(actor);
    }

    public void end(String actor) {
        require(actor, CONTROL);
        sessions.end(actor);
    }

    public FixtureResult fixture(String actor, FixtureInput input) {
        require(actor, FIXTURE, CONTROL);
        if (input == null
                || !providerAllowed(input.provider())
                || !scenarioAllowed(input.scenario()))
            throw error(HttpStatus.BAD_REQUEST, "debuggerFixtureInvalid");
        FixtureResult fixture = createFixture(input.provider(), input.scenario());
        if ("success".equals(input.scenario())
                && !sessions.addFixtureMessage(actor, input.provider()))
            throw error(HttpStatus.CONFLICT, "debuggerSessionRequired");
        return fixture;
    }

    public InvokeResponse invoke(String actor, InvokeInput input) {
        require(actor, FIXTURE, CONTROL);
        if (input == null || !providerAllowed(input.provider()) || input.body() == null)
            throw error(HttpStatus.BAD_REQUEST, "debuggerInvokeInvalid");
        if (!gate.enabled()) throw unavailable();
        var session = sessions.current(actor);
        if (session == null) throw error(HttpStatus.CONFLICT, "debuggerSessionRequired");
        String path = "webhook".equals(input.provider()) ? "send" : input.provider();
        String endpoint = receiver.endpoint(path);
        if (endpoint == null) throw unavailable();

        byte[] requestBody;
        try {
            requestBody = mapper.writeValueAsBytes(input.body());
        } catch (RuntimeException invalidBody) {
            throw error(HttpStatus.BAD_REQUEST, "debuggerInvokeInvalid");
        }
        if (requestBody.length > MAX_INVOKE_BODY_BYTES) {
            java.util.Arrays.fill(requestBody, (byte) 0);
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "debuggerInvokeTooLarge");
        }

        try {
            var request =
                    HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json");
            switch (input.provider()) {
                case "webhook" ->
                        request.header("Authorization", "Bearer " + session.bearerToken());
                case "aliyun" -> {
                    request.header(
                            "X-Lumira-Debug-Access-Key",
                            session.providers().aliyun().accessKeyId());
                    request.header(
                            "Authorization",
                            "Bearer " + session.providers().aliyun().accessKeySecret());
                }
                case "tencent" -> {
                    request.header(
                            "X-Lumira-Debug-Access-Key", session.providers().tencent().secretId());
                    request.header(
                            "Authorization", "Bearer " + session.providers().tencent().secretKey());
                }
                default -> throw error(HttpStatus.BAD_REQUEST, "debuggerInvokeInvalid");
            }
            var response =
                    httpClient.send(
                            request.POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                                    .build(),
                            BodyHandlers.ofByteArray());
            byte[] responseBody = response.body();
            try {
                Object body = mapper.readValue(responseBody, Object.class);
                return new InvokeResponse(response.statusCode(), body);
            } catch (RuntimeException invalidResponse) {
                return new InvokeResponse(502, Map.of("error", "debuggerResponseInvalid"));
            } finally {
                java.util.Arrays.fill(responseBody, (byte) 0);
            }
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new InvokeResponse(504, Map.of("error", "debuggerTimeout"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new InvokeResponse(504, Map.of("error", "debuggerTimeout"));
        } catch (IOException | IllegalArgumentException failure) {
            return new InvokeResponse(502, Map.of("error", "debuggerUnavailable"));
        } finally {
            java.util.Arrays.fill(requestBody, (byte) 0);
        }
    }

    private VerificationDebuggerReceiver.HttpResponse handleLoopbackRequest(
            String path, String accessKeyId, String authorization, byte[] requestBody) {
        if (!gate.enabled()) return http(503, Map.of("error", "debugger_disabled"));
        return switch (path) {
            case "send" -> handleWebhook(authorization, requestBody);
            case "aliyun" -> handleAliyun(accessKeyId, authorization, requestBody);
            case "tencent" -> handleTencent(accessKeyId, authorization, requestBody);
            default -> http(404, Map.of("error", "not_found"));
        };
    }

    private VerificationDebuggerReceiver.HttpResponse handleWebhook(
            String authorization, byte[] requestBody) {
        VerificationDebuggerSessions.WebhookPayload payload;
        try {
            payload =
                    mapper.readValue(
                            requestBody, VerificationDebuggerSessions.WebhookPayload.class);
        } catch (RuntimeException invalidBody) {
            return http(400, Map.of("error", "invalid_payload"));
        }
        int status = sessions.receiveWebhook(authorization, payload);
        return switch (status) {
            case 202 -> http(202, Map.of("accepted", true));
            case 401 -> http(401, Map.of("error", "unauthorized"));
            case 403 -> http(403, Map.of("error", "recipient_rejected"));
            case 429 -> http(429, Map.of("error", "rate_limited"));
            case 503 -> http(503, Map.of("error", "debugger_disabled"));
            default -> http(400, Map.of("error", "invalid_payload"));
        };
    }

    private VerificationDebuggerReceiver.HttpResponse handleAliyun(
            String accessKeyId, String authorization, byte[] requestBody) {
        var session = sessions.authenticateCloud("aliyun", accessKeyId, authorization);
        if (session == null)
            return http(200, aliyunError("InvalidAccessKeyId.NotFound", "invalid debug key"));
        Map<?, ?> values = parseObject(requestBody);
        String recipient = string(values, "PhoneNumbers");
        String signName = string(values, "SignName");
        String template = string(values, "TemplateCode");
        String code = parseAliyunCode(string(values, "TemplateParam"));
        if (!VerificationDebuggerSessions.ALLOWED_RECIPIENT.equals(recipient)
                || !VerificationDebuggerSessions.DEBUG_SENDER.equals(signName)
                || template == null
                || template.isBlank()
                || code == null) {
            return http(200, aliyunError("isv.INVALID_PARAMETERS", "invalid request parameters"));
        }
        return switch (sessions.recordCloudSend(session, "aliyun", recipient, code)) {
            case ACCEPTED -> http(200, aliyunSuccess());
            case RATE_LIMITED ->
                    http(200, aliyunError("isv.BUSINESS_LIMIT_CONTROL", "rate limit exceeded"));
            case INACTIVE ->
                    http(200, aliyunError("InvalidAccessKeyId.NotFound", "invalid debug key"));
        };
    }

    private VerificationDebuggerReceiver.HttpResponse handleTencent(
            String secretId, String authorization, byte[] requestBody) {
        var session = sessions.authenticateCloud("tencent", secretId, authorization);
        if (session == null)
            return http(
                    200,
                    tencentError("AuthFailure.SignatureFailure", "signature verification failed"));
        Map<?, ?> values = parseObject(requestBody);
        String recipient = tencentRecipient(values);
        String signName = string(values, "SignName");
        String appId = string(values, "SmsSdkAppId");
        String template = string(values, "TemplateId");
        String code = tencentCode(values);
        if (!VerificationDebuggerSessions.ALLOWED_RECIPIENT.equals(recipient)
                || !VerificationDebuggerSessions.DEBUG_SENDER.equals(signName)
                || appId == null
                || appId.isBlank()
                || template == null
                || template.isBlank()
                || code == null) {
            return http(
                    200,
                    tencentError(
                            "InvalidParameterValue.InvalidParameter",
                            "invalid request parameters"));
        }
        return switch (sessions.recordCloudSend(session, "tencent", recipient, code)) {
            case ACCEPTED -> http(200, tencentSuccess(recipient));
            case RATE_LIMITED -> http(200, tencentRateLimit(recipient));
            case INACTIVE ->
                    http(
                            200,
                            tencentError(
                                    "AuthFailure.SignatureFailure",
                                    "signature verification failed"));
        };
    }

    private Map<?, ?> parseObject(byte[] bytes) {
        try {
            Object value = mapper.readValue(bytes, Object.class);
            return value instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException invalidBody) {
            return Map.of();
        }
    }

    private String parseAliyunCode(String templateParam) {
        if (templateParam == null) return null;
        Map<?, ?> parameters =
                parseObject(templateParam.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String code = string(parameters, "code");
        return code != null && code.matches("[0-9]{6}") ? code : null;
    }

    private static String tencentRecipient(Map<?, ?> values) {
        Object raw = values.get("PhoneNumberSet");
        if (!(raw instanceof List<?> recipients) || recipients.size() != 1) return null;
        Object value = recipients.get(0);
        return value instanceof String recipient ? recipient : null;
    }

    private static String tencentCode(Map<?, ?> values) {
        Object raw = values.get("TemplateParamSet");
        if (!(raw instanceof List<?> parameters) || parameters.size() != 1) return null;
        Object value = parameters.get(0);
        if (!(value instanceof String code) || !code.matches("[0-9]{6}")) return null;
        return code;
    }

    private static String string(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof String text ? text : null;
    }

    private static boolean providerAllowed(String provider) {
        return "aliyun".equals(provider)
                || "tencent".equals(provider)
                || "webhook".equals(provider);
    }

    private static boolean scenarioAllowed(String scenario) {
        return List.of("success", "auth-failure", "rate-limit", "timeout", "malformed")
                .contains(scenario);
    }

    private FixtureResult createFixture(String provider, String scenario) {
        String path =
                switch (provider) {
                    case "aliyun" -> "/aliyun";
                    case "tencent" -> "/tencent";
                    default -> "/send";
                };
        Map<String, String> headers =
                provider.equals("webhook")
                        ? Map.of(
                                "Content-Type", "application/json",
                                "Authorization", "Bearer <session-token>")
                        : Map.of(
                                "Content-Type", "application/json",
                                "X-Lumira-Debug-Access-Key", "<session-key-id>",
                                "Authorization", "Bearer <session-secret>");
        Object requestBody = fixtureRequestBody(provider);
        FixtureResponse response = fixtureResponse(provider, scenario);
        return new FixtureResult(
                new FixtureRequest("POST", path, headers, requestBody), response, true);
    }

    private Object fixtureRequestBody(String provider) {
        return switch (provider) {
            case "aliyun" ->
                    Map.of(
                            "PhoneNumbers",
                            VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                            "SignName",
                            VerificationDebuggerSessions.DEBUG_SENDER,
                            "TemplateCode",
                            "SMS_DEBUG",
                            "TemplateParam",
                            "{\"code\":\"" + VerificationDebuggerSessions.FIXTURE_CODE + "\"}");
            case "tencent" ->
                    Map.of(
                            "PhoneNumberSet",
                            List.of(VerificationDebuggerSessions.ALLOWED_RECIPIENT),
                            "SmsSdkAppId",
                            "1400000000",
                            "TemplateId",
                            "1234",
                            "SignName",
                            VerificationDebuggerSessions.DEBUG_SENDER,
                            "TemplateParamSet",
                            List.of(VerificationDebuggerSessions.FIXTURE_CODE));
            default ->
                    Map.of(
                            "to",
                            VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                            "sender",
                            VerificationDebuggerSessions.DEBUG_SENDER,
                            "message",
                            VerificationDebuggerSessions.MESSAGE_TEMPLATE.replace(
                                    "{code}", VerificationDebuggerSessions.FIXTURE_CODE));
        };
    }

    private FixtureResponse fixtureResponse(String provider, String scenario) {
        if ("timeout".equals(scenario))
            return new FixtureResponse(504, Map.of("error", "simulated_timeout"));
        if (provider.equals("aliyun")) {
            return switch (scenario) {
                case "success" -> new FixtureResponse(200, aliyunSuccess());
                case "auth-failure" ->
                        new FixtureResponse(
                                200,
                                aliyunError("InvalidAccessKeyId.NotFound", "invalid debug key"));
                case "rate-limit" ->
                        new FixtureResponse(
                                200,
                                aliyunError("isv.BUSINESS_LIMIT_CONTROL", "rate limit exceeded"));
                default ->
                        new FixtureResponse(
                                200,
                                aliyunError(
                                        "isv.INVALID_PARAMETERS", "invalid request parameters"));
            };
        }
        if (provider.equals("tencent")) {
            return switch (scenario) {
                case "success" ->
                        new FixtureResponse(
                                200,
                                tencentSuccess(VerificationDebuggerSessions.ALLOWED_RECIPIENT));
                case "auth-failure" ->
                        new FixtureResponse(
                                200,
                                tencentError(
                                        "AuthFailure.SignatureFailure",
                                        "signature verification failed"));
                case "rate-limit" ->
                        new FixtureResponse(
                                200,
                                tencentRateLimit(VerificationDebuggerSessions.ALLOWED_RECIPIENT));
                default ->
                        new FixtureResponse(
                                200,
                                tencentError(
                                        "InvalidParameterValue.InvalidParameter",
                                        "invalid request parameters"));
            };
        }
        return switch (scenario) {
            case "success" -> new FixtureResponse(202, Map.of("accepted", true));
            case "auth-failure" -> new FixtureResponse(401, Map.of("error", "unauthorized"));
            case "rate-limit" -> new FixtureResponse(429, Map.of("error", "rate_limited"));
            default -> new FixtureResponse(400, Map.of("error", "invalid_payload"));
        };
    }

    private static Map<String, Object> aliyunSuccess() {
        return Map.of(
                "Code", "OK", "Message", "OK", "RequestId", requestId(), "BizId", requestId());
    }

    private static Map<String, Object> aliyunError(String code, String message) {
        return Map.of("Code", code, "Message", message, "RequestId", requestId());
    }

    private static Map<String, Object> tencentSuccess(String recipient) {
        return Map.of(
                "Response",
                Map.of(
                        "SendStatusSet",
                        List.of(
                                Map.of(
                                        "Code",
                                        "Ok",
                                        "Message",
                                        "send success",
                                        "PhoneNumber",
                                        recipient,
                                        "SerialNo",
                                        requestId(),
                                        "Fee",
                                        1)),
                        "RequestId",
                        requestId()));
    }

    private static Map<String, Object> tencentRateLimit(String recipient) {
        return Map.of(
                "Response",
                Map.of(
                        "SendStatusSet",
                        List.of(
                                Map.of(
                                        "Code",
                                        "LimitExceeded.PhoneNumberDailyLimit",
                                        "Message",
                                        "rate limit exceeded",
                                        "PhoneNumber",
                                        recipient,
                                        "SerialNo",
                                        "",
                                        "Fee",
                                        0)),
                        "RequestId",
                        requestId()));
    }

    private static Map<String, Object> tencentError(String code, String message) {
        return Map.of(
                "Response",
                Map.of(
                        "Error", Map.of("Code", code, "Message", message),
                        "RequestId", requestId()));
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static VerificationDebuggerReceiver.HttpResponse http(int status, Object body) {
        return new VerificationDebuggerReceiver.HttpResponse(status, body);
    }

    private void requireAvailable() {
        if (!gate.enabled() || !receiver.ready()) throw unavailable();
    }

    private void require(String actor, String... operations) {
        guard.requireGlobal(actor, ACCESS);
        for (String operation : operations) guard.requireGlobal(actor, operation);
    }

    private static ResponseStatusException unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "debuggerUnavailable");
    }

    private static ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
