package com.lumira.platform.sms;

import com.lumira.platform.common.SmsConfigurationPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class SmsGatewayTest {
    @Test void localTestPostsSyntheticFieldsWithTheConfiguredBearerKeyOnlyToLoopback() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        byte[] credential = "synthetic-fixture-key".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        });
        server.start();
        try {
            var settings = new SmsConfigurationPort.Settings(true, "webhook",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/send", "Lumira", "Code {code}", 5);
            var result = new SmsGateway(new ObjectMapper()).send(new SmsConnection(settings, credential),
                new OutgoingSms("+15555550123", "Lumira local SMS transport test. No account code is included."));

            assertEquals(SmsGateway.Result.ACCEPTED, result);
            assertEquals("Bearer synthetic-fixture-key", authorization.get());
            var payload = new ObjectMapper().readTree(body.get());
            assertEquals("+15555550123", payload.path("to").asString());
            assertEquals("Lumira", payload.path("sender").asString());
            assertEquals("Lumira local SMS transport test. No account code is included.", payload.path("message").asString());
            assertArrayEquals(new byte[credential.length], credential);
        } finally {
            server.stop(0);
            Arrays.fill(credential, (byte) 0);
        }
    }

    @Test void localTestDoesNotFollowReceiverRedirects() throws Exception {
        AtomicReference<Boolean> redirectTargetHit = new AtomicReference<>(false);
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/final", exchange -> {
            redirectTargetHit.set(true);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        target.start();
        HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/send", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirector.start();
        try {
            var settings = new SmsConfigurationPort.Settings(true, "webhook",
                "http://127.0.0.1:" + redirector.getAddress().getPort() + "/send", "Lumira", "Code {code}", 5);
            var result = new SmsGateway(new ObjectMapper()).send(new SmsConnection(settings,
                "synthetic-redirect-test-key".getBytes(StandardCharsets.UTF_8)),
                new OutgoingSms("+15555550123", "Synthetic local test"));
            assertEquals(SmsGateway.Result.CONNECTION_FAILED, result);
            assertFalse(redirectTargetHit.get());
        } finally {
            redirector.stop(0);
            target.stop(0);
        }
    }

    @Test void webhookCarriesOnlyTheProviderContractAndAcceptsSuccess() throws Exception {
        AtomicReference<String> authorization=new AtomicReference<>(); AtomicReference<String> body=new AtomicReference<>();
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String syntheticToken=Base64.getUrlEncoder().withoutPadding().encodeToString(random);Arrays.fill(random,(byte)0);
        byte[] credential=syntheticToken.getBytes(StandardCharsets.UTF_8);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/send", exchange->{authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));exchange.sendResponseHeaders(202,0);exchange.close();});server.start();
        try {
            var settings=new SmsConfigurationPort.Settings(true,"webhook","http://127.0.0.1:"+server.getAddress().getPort()+"/send","Lumira","Code {code}",5);
            var result=new SmsGateway(new ObjectMapper()).send(new SmsConnection(settings,credential),new OutgoingSms("+15555550123","Code 123"));
            assertEquals(SmsGateway.Result.ACCEPTED,result);assertEquals("Bearer "+syntheticToken,authorization.get());assertTrue(body.get().contains("+15555550123"));assertTrue(body.get().contains("Code 123"));assertTrue(body.get().contains("Lumira"));assertArrayEquals(new byte[credential.length],credential);
        } finally { server.stop(0);Arrays.fill(credential,(byte)0); }
    }
    @Test void authenticationFailureIsNotRetried() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/send",exchange->{exchange.sendResponseHeaders(401,-1);exchange.close();});server.start();
        try {
            var settings=new SmsConfigurationPort.Settings(true,"webhook","http://127.0.0.1:"+server.getAddress().getPort()+"/send","Lumira","Code {code}",5);
            assertEquals(SmsGateway.Result.AUTHENTICATION_FAILED,new SmsGateway(new ObjectMapper()).send(new SmsConnection(settings,new byte[]{1}),new OutgoingSms("+8613800138000","Code")));
        } finally { server.stop(0); }
    }
}
