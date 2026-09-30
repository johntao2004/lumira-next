package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PublicCloudDnsTest {
    @Test
    void publicSystemAnswersStayOnSystemDnsWithoutCallingDoh() throws Exception {
        AtomicInteger dohCalls = new AtomicInteger();
        var dns =
                new PublicCloudDns(
                        host -> new InetAddress[] {address("3.173.21.63")},
                        (host, type) -> {
                            dohCalls.incrementAndGet();
                            throw new IOException("unexpected DoH request");
                        },
                        System::nanoTime);

        assertEquals(List.of(address("3.173.21.63")), List.of(dns.resolve("api.deepseek.com")));
        assertEquals(0, dohCalls.get());
    }

    @Test
    void syntheticSystemAnswersUseBothDohFamiliesAndCacheOnlyTheSuccessfulResult()
            throws Exception {
        AtomicInteger dohCalls = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000_000_000L);
        var dns =
                new PublicCloudDns(
                        host -> new InetAddress[] {address("198.18.0.136")},
                        (host, type) -> {
                            dohCalls.incrementAndGet();
                            return type == 1
                                    ? new PublicCloudDns.DnsAnswer(
                                            List.of(address("3.173.21.63")), 120)
                                    : new PublicCloudDns.DnsAnswer(
                                            List.of(address("2606:4700:4700::1111")), 120);
                        },
                        now::get);

        var first = List.of(dns.resolve("api.deepseek.com"));
        var second = List.of(dns.resolve("api.deepseek.com"));
        assertEquals(List.of(address("3.173.21.63"), address("2606:4700:4700::1111")), first);
        assertEquals(first, second);
        assertEquals(2, dohCalls.get());

        now.addAndGet(59_000_000_000L);
        dns.resolve("api.deepseek.com");
        assertEquals(2, dohCalls.get(), "fallback cache is capped at 60 seconds");
        now.addAndGet(2_000_000_000L);
        dns.resolve("api.deepseek.com");
        assertEquals(4, dohCalls.get(), "expired fallback answers must be refreshed");
    }

    @Test
    void translatedSyntheticAnswersTriggerFallbackAndPrivateMixesNeverDo() throws Exception {
        AtomicInteger dohCalls = new AtomicInteger();
        var translated =
                new PublicCloudDns(
                        host -> new InetAddress[] {address("::ffff:0:c612:88")},
                        (host, type) -> {
                            dohCalls.incrementAndGet();
                            return new PublicCloudDns.DnsAnswer(
                                    List.of(address("3.173.21.63")), 10);
                        },
                        System::nanoTime);
        assertEquals(
                List.of(address("3.173.21.63")), List.of(translated.resolve("api.example.com")));
        assertEquals(2, dohCalls.get());

        for (List<String> systemAnswers :
                List.of(
                        List.of("10.1.2.3"),
                        List.of("198.18.0.136", "192.168.1.5"),
                        List.of("198.18.0.136", "169.254.169.254"))) {
            AtomicInteger blockedDohCalls = new AtomicInteger();
            var blocked =
                    new PublicCloudDns(
                            host ->
                                    systemAnswers.stream()
                                            .map(PublicCloudDnsTest::address)
                                            .toArray(InetAddress[]::new),
                            (host, type) -> {
                                blockedDohCalls.incrementAndGet();
                                return new PublicCloudDns.DnsAnswer(
                                        List.of(address("3.173.21.63")), 10);
                            },
                            System::nanoTime);
            assertThrows(IOException.class, () -> blocked.resolve("api.example.com"));
            assertEquals(0, blockedDohCalls.get());
        }
    }

    @Test
    void rejectsAnyUnsafeDohAddressInsteadOfFilteringItOut() throws Exception {
        var dns =
                new PublicCloudDns(
                        host -> new InetAddress[] {address("198.18.0.1")},
                        (host, type) ->
                                new PublicCloudDns.DnsAnswer(
                                        type == 1
                                                ? List.of(address("3.173.21.63"))
                                                : List.of(address("10.0.0.1")),
                                        10),
                        System::nanoTime);
        assertThrows(IOException.class, () -> dns.resolve("api.example.com"));
    }

    @Test
    void validatesTheWholeDnsQuestionAndCnameAnswerChain() throws Exception {
        String valid =
                """
                {"Status":0,"TC":false,"Question":[{"name":"API.EXAMPLE.COM.","type":1}],
                 "Answer":[{"name":"api.example.com","type":5,"TTL":30,"data":"edge.example.net."},
                           {"name":"edge.example.net","type":1,"TTL":20,"data":"8.8.8.8"}]}
                """;
        var answer =
                PublicCloudDns.parseResponse(
                        "api.example.com", 1, valid.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(address("8.8.8.8")), answer.addresses());
        assertEquals(20, answer.ttlSeconds());

        String mismatchedQuestion =
                valid.replace("API.EXAMPLE.COM.", "api.example.com.attacker.test.");
        assertThrows(
                IOException.class,
                () ->
                        PublicCloudDns.parseResponse(
                                "api.example.com",
                                1,
                                mismatchedQuestion.getBytes(StandardCharsets.UTF_8)));
        String unrelatedAnswer =
                valid.replace(
                        "\"name\":\"edge.example.net\",\"type\":1",
                        "\"name\":\"other.example.net\",\"type\":1");
        assertThrows(
                IOException.class,
                () ->
                        PublicCloudDns.parseResponse(
                                "api.example.com",
                                1,
                                unrelatedAnswer.getBytes(StandardCharsets.UTF_8)));
        String truncated = valid.replace("\"TC\":false", "\"TC\":true");
        assertThrows(
                IOException.class,
                () ->
                        PublicCloudDns.parseResponse(
                                "api.example.com", 1, truncated.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void doesNotCacheFailedFallbackRequests() throws Exception {
        AtomicBoolean failQueries = new AtomicBoolean(true);
        AtomicInteger successfulCalls = new AtomicInteger();
        var dns =
                new PublicCloudDns(
                        host -> new InetAddress[] {address("198.18.0.136")},
                        (host, type) -> {
                            if (failQueries.get()) throw new IOException("temporary DoH failure");
                            successfulCalls.incrementAndGet();
                            return new PublicCloudDns.DnsAnswer(
                                    List.of(address("3.173.21.63")), 30);
                        },
                        System::nanoTime);
        assertThrows(IOException.class, () -> dns.resolve("api.example.com"));
        failQueries.set(false);
        assertEquals(List.of(address("3.173.21.63")), List.of(dns.resolve("api.example.com")));
        assertEquals(2, successfulCalls.get());
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException invalid) {
            throw new AssertionError(invalid);
        }
    }
}
