package com.lumira.platform.sms;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.GuardedSockets;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SmsCloudGatewayTest {
    final SmsGateway gateway =
            new SmsGateway(
                    new ObjectMapper(),
                    (r, t) -> {
                        throw new AssertionError("No network expected");
                    });

    byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void successfulHttpDoesNotConcealProviderFailure() {
        assertEquals(
                SmsGateway.Result.ACCEPTED,
                gateway.classify(
                        "tencent",
                        200,
                        bytes("{\"Response\":{\"SendStatusSet\":[{\"Code\":\"Ok\"}]}}")));
        assertEquals(
                SmsGateway.Result.PROVIDER_REJECTED,
                gateway.classify(
                        "tencent",
                        200,
                        bytes(
                                "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"FailedOperation.TemplateParamSetNotMatchApprovedTemplate\"}]}}")));
        assertEquals(
                SmsGateway.Result.ACCEPTED,
                gateway.classify("aliyun", 200, bytes("{\"Code\":\"OK\"}")));
        assertEquals(
                SmsGateway.Result.PROVIDER_REJECTED,
                gateway.classify("aliyun", 200, bytes("{\"Code\":\"isv.TEMPLATE_ILLEGAL\"}")));
        assertEquals(
                SmsGateway.Result.ACCEPTED,
                gateway.classify("alibaba_intl", 200, bytes("{\"ResponseCode\":\"OK\"}")));
        assertEquals(
                SmsGateway.Result.ACCEPTED,
                gateway.classify(
                        "huawei",
                        200,
                        bytes("{\"code\":\"000000\",\"result\":[{\"status\":\"000000\"}]}")));
        assertEquals(
                SmsGateway.Result.PROVIDER_REJECTED,
                gateway.classify(
                        "huawei",
                        200,
                        bytes("{\"code\":\"000000\",\"result\":[{\"status\":\"E200028\"}]}")));
        assertEquals(
                SmsGateway.Result.ACCEPTED,
                gateway.classify(
                        "twilio",
                        201,
                        bytes(
                                "{\"sid\":\"SMaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"status\":\"queued\"}")));
        assertEquals(
                SmsGateway.Result.DELIVERY_UNCERTAIN,
                gateway.classify("twilio", 201, bytes("{\"status\":\"queued\"}")));
    }

    @Test
    void authErrorsAreTerminalAndMalformedOrTimeoutResponsesAreUncertain() {
        assertEquals(
                SmsGateway.Result.AUTHENTICATION_FAILED,
                gateway.classify(
                        "tencent",
                        200,
                        bytes(
                                "{\"Response\":{\"Error\":{\"Code\":\"AuthFailure.SignatureFailure\"}}}")));
        assertEquals(
                SmsGateway.Result.AUTHENTICATION_FAILED,
                gateway.classify(
                        "aliyun", 400, bytes("{\"Code\":\"InvalidAccessKeyId.NotFound\"}")));
        assertEquals(
                SmsGateway.Result.AUTHENTICATION_FAILED,
                gateway.classify("huawei", 401, bytes("{}")));
        assertEquals(
                SmsGateway.Result.CONNECTION_FAILED,
                gateway.classify(
                        "tencent",
                        200,
                        bytes("{\"Response\":{\"Error\":{\"Code\":\"RequestLimitExceeded\"}}}")));
        for (String provider :
                new String[] {"tencent", "aliyun", "alibaba_intl", "huawei", "twilio"}) {
            assertEquals(
                    SmsGateway.Result.DELIVERY_UNCERTAIN,
                    gateway.classify(provider, 200, bytes("<html>not json</html>")));
            assertEquals(
                    SmsGateway.Result.DELIVERY_UNCERTAIN,
                    gateway.classify(provider, 503, bytes("{}")));
            assertEquals(
                    SmsGateway.Result.CONNECTION_FAILED,
                    gateway.classify(provider, 429, bytes("{}")));
        }
    }

    @Test
    void dispatchesEveryProviderWithoutRealNetworkAndWipesSecrets() {
        for (String provider :
                new String[] {"tencent", "aliyun", "alibaba_intl", "huawei", "twilio", "webhook"}) {
            var settings =
                    SmsProviderProtocolTest.settings(
                            provider, provider.equals("twilio") ? "international" : "domestic");
            byte[] secret = bytes("fixture-secret");
            AtomicInteger calls = new AtomicInteger();
            var sender =
                    new SmsGateway(
                            new ObjectMapper(),
                            (request, timeout) -> {
                                calls.incrementAndGet();
                                assertEquals(
                                        provider.equals("alibaba_intl") ? "GET" : "POST",
                                        request.method());
                                String response =
                                        switch (provider) {
                                            case "tencent" ->
                                                    "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"Ok\"}]}}";
                                            case "aliyun" -> "{\"Code\":\"OK\"}";
                                            case "alibaba_intl" -> "{\"ResponseCode\":\"OK\"}";
                                            case "huawei" ->
                                                    "{\"code\":\"000000\",\"result\":[{\"status\":\"000000\"}]}";
                                            case "twilio" ->
                                                    "{\"sid\":\"SMaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"status\":\"queued\"}";
                                            default -> "";
                                        };
                                return new GuardedSockets.Response(
                                        provider.equals("twilio") ? 201 : 200, bytes(response));
                            });
            assertEquals(
                    SmsGateway.Result.ACCEPTED,
                    sender.send(
                            new SmsConnection(settings, secret),
                            SmsProviderProtocolTest.sms(
                                    settings,
                                    provider.equals("twilio")
                                            ? "+15555550123"
                                            : "+8613800138000")));
            assertEquals(1, calls.get());
            assertArrayEquals(new byte[secret.length], secret);
        }
    }

    @Test
    void failuresAfterWritingAreNeverAutomaticallyRetried() {
        var settings = SmsProviderProtocolTest.settings("aliyun", "domestic");
        for (java.io.IOException error :
                new java.io.IOException[] {
                    new java.net.http.HttpTimeoutException("fixture"),
                    new java.io.IOException("response read failed"),
                    new java.net.ConnectException("connect failed")
                }) {
            byte[] secret = bytes("fixture-secret");
            var sender =
                    new SmsGateway(
                            new ObjectMapper(),
                            (r, t) -> {
                                throw error;
                            });
            assertEquals(
                    error instanceof java.net.ConnectException
                            ? SmsGateway.Result.CONNECTION_FAILED
                            : SmsGateway.Result.DELIVERY_UNCERTAIN,
                    sender.send(
                            new SmsConnection(settings, secret),
                            SmsProviderProtocolTest.sms(settings, "+8613800138000")));
            assertArrayEquals(new byte[secret.length], secret);
        }
    }
}
