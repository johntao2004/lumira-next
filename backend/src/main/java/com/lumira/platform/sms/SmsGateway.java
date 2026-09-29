package com.lumira.platform.sms;

import com.lumira.platform.common.SmsConfigurationPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Provider-neutral JSON webhook transport. A provider response is never logged or exposed. */
@Component
public class SmsGateway {
    public enum Result {
        ACCEPTED,
        AUTHENTICATION_FAILED,
        CONNECTION_FAILED,
        DELIVERY_UNCERTAIN
    }

    private final HttpClient client;
    private final ObjectMapper mapper;

    @Autowired
    public SmsGateway(ObjectMapper mapper) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                mapper);
    }

    SmsGateway(HttpClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public Result send(SmsConnection connection, OutgoingSms sms) {
        byte[] secret = connection.secret();
        try (connection) {
            SmsConfigurationPort.Settings data = connection.settings();
            if (!data.enabled() || sms == null || secret == null || secret.length == 0)
                return Result.CONNECTION_FAILED;
            String credential = new String(secret, StandardCharsets.UTF_8);
            // Header values cannot contain control characters. Treat a corrupted or
            // malformed credential as a terminal authentication failure instead of
            // allowing the JDK request builder to throw and retrying the delivery.
            if (credential.isBlank() || credential.codePoints().anyMatch(Character::isISOControl)) {
                return Result.AUTHENTICATION_FAILED;
            }
            URI target = URI.create(data.endpoint());
            String host = target.getHost();
            boolean loopback =
                    host != null
                            && (host.equalsIgnoreCase("localhost")
                                    || host.equals("127.0.0.1")
                                    || host.equals("[::1]")
                                    || host.equals("::1"));
            // Re-check at connect time so a name that later resolves to an internal address is
            // never contacted.
            if (!loopback && !com.lumira.platform.common.PublicHosts.resolvesPublic(host))
                return Result.CONNECTION_FAILED;
            String payload =
                    mapper.writeValueAsString(
                            new Payload(sms.recipient(), data.sender(), sms.text()));
            HttpRequest request =
                    HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(data.timeoutSeconds()))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + credential)
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            payload, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<Void> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.DELIVERY_UNCERTAIN;
            } catch (java.io.IOException | java.util.concurrent.CompletionException failed) {
                return Result.CONNECTION_FAILED;
            }
            int status = response.statusCode();
            if (status == 401 || status == 403) return Result.AUTHENTICATION_FAILED;
            if (status >= 200 && status < 300) return Result.ACCEPTED;
            // The provider received an HTTP request, so the final delivery state is not safe to
            // retry automatically.
            if (status >= 400 && status < 500 && status != 408 && status != 429)
                return Result.DELIVERY_UNCERTAIN;
            return Result.CONNECTION_FAILED;
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    static boolean isLoopbackTestEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 2000) return false;
        try {
            URI uri = URI.create(endpoint);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() > 0
                    && uri.getPort() <= 65535
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && "/send".equals(uri.getRawPath());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private record Payload(String to, String sender, String message) {}
}
