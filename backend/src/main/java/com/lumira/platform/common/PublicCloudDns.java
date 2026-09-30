package com.lumira.platform.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Public-cloud host resolution with a narrow fallback for DNS interception synthetic answers. */
final class PublicCloudDns {
    private static final String DOH_HOST = "cloudflare-dns.com";
    private static final int DNS_TYPE_A = 1;
    private static final int DNS_TYPE_AAAA = 28;
    private static final int MAX_DOH_BODY_BYTES = 16 * 1024;
    private static final int MAX_DNS_RECORDS = 64;
    private static final int MAX_CNAME_DEPTH = 8;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final long MAX_CACHE_TTL_SECONDS = 60;
    private static final long DOH_TOTAL_TIMEOUT_MILLIS = 3_700;
    private static final Duration DOH_CONNECT_TIMEOUT = Duration.ofMillis(900);
    private static final Duration DOH_READ_TIMEOUT = Duration.ofMillis(3_000);
    private static final InetAddress DOH_BOOTSTRAP = bootstrapAddress();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService DOH_EXECUTOR =
            new ThreadPoolExecutor(
                    2,
                    4,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    task -> {
                        Thread thread = new Thread(task, "lumira-public-doh");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private static final PublicCloudDns INSTANCE =
            new PublicCloudDns(
                    InetAddress::getAllByName, PublicCloudDns::queryCloudflare, System::nanoTime);

    @FunctionalInterface
    interface SystemResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    @FunctionalInterface
    interface DohQuery {
        DnsAnswer query(String host, int type) throws IOException;
    }

    record DnsAnswer(List<InetAddress> addresses, long ttlSeconds) {
        DnsAnswer {
            addresses = List.copyOf(addresses);
        }
    }

    private record CacheEntry(List<InetAddress> addresses, long expiresAtNanos) {
        private CacheEntry {
            addresses = List.copyOf(addresses);
        }
    }

    private final SystemResolver systemResolver;
    private final DohQuery dohQuery;
    private final LongSupplier nanoClock;
    private final Map<String, CacheEntry> fallbackCache = new LinkedHashMap<>(16, 0.75f, true);

    PublicCloudDns(SystemResolver systemResolver, DohQuery dohQuery, LongSupplier nanoClock) {
        this.systemResolver = systemResolver;
        this.dohQuery = dohQuery;
        this.nanoClock = nanoClock;
    }

    static InetAddress[] resolvePublic(String host) throws IOException {
        return INSTANCE.resolve(host);
    }

    InetAddress[] resolve(String host) throws IOException {
        if (!PublicHosts.isAllowed(host)) throw new IOException("Destination not permitted");
        String normalized = unbracket(host).toLowerCase(Locale.ROOT);
        if (isIpLiteral(normalized)) {
            InetAddress literal = parseIpLiteral(normalized);
            if (!PublicHosts.isPublicAddress(literal))
                throw new IOException("Destination not permitted");
            return new InetAddress[] {literal};
        }

        InetAddress[] systemAnswers = systemResolver.resolve(normalized);
        if (systemAnswers == null || systemAnswers.length == 0)
            throw new UnknownHostException(normalized);

        boolean hasSyntheticAnswer = false;
        for (InetAddress address : systemAnswers) {
            if (PublicHosts.isSyntheticAddress(address)) {
                hasSyntheticAnswer = true;
            } else if (!PublicHosts.isPublicAddress(address)) {
                // Real private, reserved, or mixed-private answers are never eligible for fallback.
                throw new IOException("Destination not permitted");
            }
        }
        if (!hasSyntheticAnswer) return distinct(systemAnswers).toArray(InetAddress[]::new);

        List<InetAddress> fallbackAddresses = cachedFallback(normalized);
        if (fallbackAddresses == null) {
            DnsAnswer[] answers = queryBothFamilies(normalized);
            var checked = new LinkedHashSet<InetAddress>();
            long ttl = Long.MAX_VALUE;
            for (DnsAnswer answer : answers) {
                if (answer.ttlSeconds() <= 0) ttl = 0;
                else if (ttl > 0) ttl = Math.min(ttl, answer.ttlSeconds());
                for (InetAddress address : answer.addresses()) {
                    if (!PublicHosts.isPublicAddress(address))
                        throw new IOException("Destination not permitted");
                    checked.add(address);
                }
            }
            if (checked.isEmpty()) throw new UnknownHostException(normalized);
            fallbackAddresses = List.copyOf(checked);
            cacheFallback(normalized, fallbackAddresses, ttl);
        }
        return fallbackAddresses.toArray(InetAddress[]::new);
    }

    private synchronized List<InetAddress> cachedFallback(String host) {
        CacheEntry cached = fallbackCache.get(host);
        if (cached == null) return null;
        if (cached.expiresAtNanos() <= nanoClock.getAsLong()) {
            fallbackCache.remove(host);
            return null;
        }
        return cached.addresses();
    }

    private synchronized void cacheFallback(
            String host, List<InetAddress> addresses, long ttlSeconds) {
        if (ttlSeconds <= 0) return;
        long boundedTtl = Math.min(MAX_CACHE_TTL_SECONDS, ttlSeconds);
        long ttlNanos = TimeUnit.SECONDS.toNanos(boundedTtl);
        long expiresAt = nanoClock.getAsLong() + ttlNanos;
        fallbackCache.put(host, new CacheEntry(addresses, expiresAt));
        while (fallbackCache.size() > MAX_CACHE_ENTRIES) {
            String eldest = fallbackCache.keySet().iterator().next();
            fallbackCache.remove(eldest);
        }
    }

    private DnsAnswer[] queryBothFamilies(String host) throws IOException {
        Future<DnsAnswer> ipv4 = null;
        Future<DnsAnswer> ipv6 = null;
        try {
            ipv4 = submitQuery(host, DNS_TYPE_A);
            ipv6 = submitQuery(host, DNS_TYPE_AAAA);
            long deadline =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DOH_TOTAL_TIMEOUT_MILLIS);
            DnsAnswer a = getBeforeDeadline(ipv4, deadline);
            DnsAnswer aaaa = getBeforeDeadline(ipv6, deadline);
            return new DnsAnswer[] {a, aaaa};
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Public DNS fallback interrupted", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException ioFailure) throw ioFailure;
            throw new IOException("Public DNS fallback failed", cause);
        } catch (TimeoutException timedOut) {
            throw new IOException("Public DNS fallback timed out", timedOut);
        } catch (RejectedExecutionException overloaded) {
            throw new IOException("Public DNS fallback is busy", overloaded);
        } finally {
            if (ipv4 != null && !ipv4.isDone()) ipv4.cancel(true);
            if (ipv6 != null && !ipv6.isDone()) ipv6.cancel(true);
        }
    }

    private Future<DnsAnswer> submitQuery(String host, int type) {
        Callable<DnsAnswer> query = () -> dohQuery.query(host, type);
        return DOH_EXECUTOR.submit(query);
    }

    private static DnsAnswer getBeforeDeadline(Future<DnsAnswer> query, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return query.get(remaining, TimeUnit.NANOSECONDS);
    }

    private static DnsAnswer queryCloudflare(String host, int type) throws IOException {
        String recordType = type == DNS_TYPE_A ? "A" : "AAAA";
        URI uri =
                URI.create(
                        "https://"
                                + DOH_HOST
                                + "/dns-query?name="
                                + URLEncoder.encode(host, StandardCharsets.UTF_8)
                                + "&type="
                                + recordType);
        GuardedSockets.Response response =
                GuardedSockets.exchangePinned(
                        uri,
                        "GET",
                        Map.of("Accept", "application/dns-json"),
                        null,
                        DOH_CONNECT_TIMEOUT,
                        DOH_READ_TIMEOUT,
                        MAX_DOH_BODY_BYTES + 1,
                        queryHost -> {
                            if (!DOH_HOST.equalsIgnoreCase(queryHost))
                                throw new IOException("Unexpected DNS resolver host");
                            return new InetAddress[] {DOH_BOOTSTRAP};
                        });
        if (response.status() != 200 || response.body().length > MAX_DOH_BODY_BYTES)
            throw new IOException("Public DNS resolver returned an invalid response");
        return parseResponse(host, type, response.body());
    }

    static DnsAnswer parseResponse(String queryName, int queryType, byte[] body)
            throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException invalidJson) {
            throw new IOException("Public DNS resolver returned invalid JSON", invalidJson);
        }
        if (root == null
                || !root.isObject()
                || root.path("Status").asInt(-1) != 0
                || root.path("TC").asBoolean(true))
            throw new IOException("Public DNS resolver returned an incomplete answer");

        JsonNode questions = root.path("Question");
        if (!questions.isArray() || questions.size() != 1)
            throw new IOException("Public DNS resolver returned an invalid question");
        JsonNode question = questions.get(0);
        if (question == null
                || !normalizeDnsName(question.path("name").asText(""))
                        .equals(normalizeDnsName(queryName))
                || question.path("type").asInt(-1) != queryType)
            throw new IOException("Public DNS resolver question did not match");

        JsonNode answers = root.path("Answer");
        if (!answers.isMissingNode() && !answers.isNull() && !answers.isArray())
            throw new IOException("Public DNS resolver returned invalid records");
        if (answers.isArray() && answers.size() > MAX_DNS_RECORDS)
            throw new IOException("Public DNS resolver returned too many records");

        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, List<InetAddress>> addresses = new LinkedHashMap<>();
        Map<String, Long> ttls = new LinkedHashMap<>();
        if (answers.isArray()) {
            for (JsonNode answer : answers) {
                String owner = normalizeDnsName(answer.path("name").asText(""));
                if (!isDnsName(owner))
                    throw new IOException("Public DNS resolver returned invalid owner");
                int type = answer.path("type").asInt(-1);
                long ttl = answer.path("TTL").asLong(-1);
                if (ttl < 0) throw new IOException("Public DNS resolver returned invalid TTL");
                ttls.merge(owner, ttl, Math::min);
                String data = answer.path("data").asText("");
                if (type == 5) {
                    String target = normalizeDnsName(data);
                    if (!isDnsName(target))
                        throw new IOException("Public DNS resolver returned invalid CNAME");
                    String previous = aliases.putIfAbsent(owner, target);
                    if (previous != null && !previous.equals(target))
                        throw new IOException("Public DNS resolver returned conflicting CNAMEs");
                } else if (type == DNS_TYPE_A || type == DNS_TYPE_AAAA) {
                    InetAddress address = parseAddressData(data, type);
                    addresses.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(address);
                } else {
                    throw new IOException("Public DNS resolver returned an unsupported record");
                }
            }
        }

        String normalizedQuery = normalizeDnsName(queryName);
        var chain = new LinkedHashSet<String>();
        String current = normalizedQuery;
        for (int depth = 0; depth <= MAX_CNAME_DEPTH; depth++) {
            if (!chain.add(current))
                throw new IOException("Public DNS resolver returned a CNAME loop");
            String next = aliases.get(current);
            if (next == null) break;
            if (depth == MAX_CNAME_DEPTH)
                throw new IOException("Public DNS resolver returned a long CNAME chain");
            current = next;
        }
        if (!chain.containsAll(aliases.keySet()) || !chain.containsAll(addresses.keySet()))
            throw new IOException("Public DNS resolver returned an unrelated record");

        var result = new LinkedHashSet<InetAddress>();
        for (String owner : chain) result.addAll(addresses.getOrDefault(owner, List.of()));
        long minimumTtl = Long.MAX_VALUE;
        for (String owner : chain) {
            Long ttl = ttls.get(owner);
            if (ttl != null) minimumTtl = Math.min(minimumTtl, ttl);
        }
        long ttl = minimumTtl == Long.MAX_VALUE ? 0 : minimumTtl;
        return new DnsAnswer(List.copyOf(result), ttl);
    }

    private static InetAddress parseAddressData(String data, int type) throws IOException {
        try {
            if (type == DNS_TYPE_A) {
                String[] octets = data.split("\\.", -1);
                if (octets.length != 4) throw new IOException("Invalid IPv4 DNS answer");
                byte[] bytes = new byte[4];
                for (int index = 0; index < octets.length; index++) {
                    if (!octets[index].matches("\\d{1,3}"))
                        throw new IOException("Invalid IPv4 DNS answer");
                    int value = Integer.parseInt(octets[index]);
                    if (value > 255) throw new IOException("Invalid IPv4 DNS answer");
                    bytes[index] = (byte) value;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (!data.contains(":")) throw new IOException("Invalid IPv6 DNS answer");
            InetAddress address = InetAddress.getByName(data);
            if (address.getAddress().length != 16) throw new IOException("Invalid IPv6 DNS answer");
            return address;
        } catch (UnknownHostException | NumberFormatException invalid) {
            throw new IOException("Invalid IP address in DNS answer", invalid);
        }
    }

    private static List<InetAddress> distinct(InetAddress[] addresses) {
        return List.copyOf(new LinkedHashSet<>(Arrays.asList(addresses)));
    }

    private static String normalizeDnsName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static boolean isDnsName(String name) {
        return name.length() <= 253
                && !name.isEmpty()
                && name.matches(
                        "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || host.contains(":");
    }

    private static InetAddress parseIpLiteral(String host) throws IOException {
        try {
            if (host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
                return parseAddressData(host, DNS_TYPE_A);
            }
            return InetAddress.getByName(host);
        } catch (UnknownHostException invalid) {
            throw new IOException("Invalid IP literal", invalid);
        }
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private static InetAddress bootstrapAddress() {
        try {
            return InetAddress.getByAddress(new byte[] {1, 1, 1, 1});
        } catch (UnknownHostException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}
