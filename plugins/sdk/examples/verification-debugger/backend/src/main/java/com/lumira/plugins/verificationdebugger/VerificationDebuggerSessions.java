package com.lumira.plugins.verificationdebugger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Short-lived fake credentials and owner-private OTP messages. */
final class VerificationDebuggerSessions implements AutoCloseable {
    static final String ALLOWED_RECIPIENT = "+15555550123";
    static final String DEBUG_SENDER = "LumiraDebug";
    static final String MESSAGE_TEMPLATE = "Lumira verification code: {code}";

    private static final Duration SESSION_TTL = Duration.ofMinutes(10);
    private static final Duration MESSAGE_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_SESSIONS = 50;
    private static final int MAX_MESSAGES = 50;
    private static final int MAX_SENDS_PER_WINDOW = 5;
    private static final Pattern MESSAGE_CODE =
            Pattern.compile("^Lumira verification code: ([0-9]{6})$");
    private static final Pattern BEARER = Pattern.compile("(?i)^Bearer ([A-Za-z0-9_-]{43})$");
    static final String FIXTURE_CODE = "135790";

    record AliyunCredentials(String endpoint, String accessKeyId, String accessKeySecret) {
        @Override
        public String toString() {
            return "AliyunCredentials[endpoint=" + endpoint + ", credentials=redacted]";
        }
    }

    record TencentCredentials(String endpoint, String secretId, String secretKey) {
        @Override
        public String toString() {
            return "TencentCredentials[endpoint=" + endpoint + ", credentials=redacted]";
        }
    }

    record ProviderCredentials(AliyunCredentials aliyun, TencentCredentials tencent) {}

    record SessionView(
            UUID id,
            Instant expiresAt,
            String endpoint,
            String bearerToken,
            String recipient,
            ProviderCredentials providers) {
        @Override
        public String toString() {
            return "SessionView[id=" + id + ", expiresAt=" + expiresAt + ", credentials=redacted]";
        }
    }

    record WebhookPayload(String to, String sender, String message) {}

    record Message(
            UUID id,
            Instant receivedAt,
            String recipient,
            String message,
            String code,
            String provider,
            boolean simulated) {}

    record MessagePage(List<Message> items, String cursor) {}

    record ActiveSession(UUID id, String owner) {}

    enum SendResult {
        ACCEPTED,
        RATE_LIMITED,
        INACTIVE
    }

    private static final class Secret {
        private final String id;
        private final byte[] bytes;
        private final byte[] digest;

        private Secret(String id, byte[] bytes) {
            this.id = id;
            this.bytes = bytes;
            this.digest = sha256(bytes);
        }

        private String value() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        private boolean matches(String suppliedId, String authorization) {
            String token = "";
            if (authorization != null) {
                Matcher matcher = BEARER.matcher(authorization.strip());
                if (matcher.matches()) token = matcher.group(1);
            }
            byte[] candidate;
            try {
                candidate = Base64.getUrlDecoder().decode(token);
            } catch (IllegalArgumentException invalidToken) {
                candidate = token.getBytes(StandardCharsets.UTF_8);
            }
            byte[] candidateDigest = sha256(candidate);
            Arrays.fill(candidate, (byte) 0);
            boolean idMatches =
                    id == null
                            || MessageDigest.isEqual(
                                    id.getBytes(StandardCharsets.UTF_8),
                                    (suppliedId == null ? "" : suppliedId)
                                            .getBytes(StandardCharsets.UTF_8));
            boolean secretMatches = MessageDigest.isEqual(digest, candidateDigest);
            Arrays.fill(candidateDigest, (byte) 0);
            return idMatches & secretMatches;
        }

        private void erase() {
            Arrays.fill(bytes, (byte) 0);
            Arrays.fill(digest, (byte) 0);
        }
    }

    private static final class Session {
        private final UUID id;
        private final String owner;
        private final Instant expiresAt;
        private final String baseEndpoint;
        private final Secret webhook;
        private final Secret aliyun;
        private final Secret tencent;
        private final Deque<Instant> sendTimes = new ArrayDeque<>();

        private Session(
                UUID id,
                String owner,
                Instant expiresAt,
                String baseEndpoint,
                Secret webhook,
                Secret aliyun,
                Secret tencent) {
            this.id = id;
            this.owner = owner;
            this.expiresAt = expiresAt;
            this.baseEndpoint = baseEndpoint;
            this.webhook = webhook;
            this.aliyun = aliyun;
            this.tencent = tencent;
        }

        private SessionView view() {
            return new SessionView(
                    id,
                    expiresAt,
                    baseEndpoint + "/send",
                    webhook.value(),
                    ALLOWED_RECIPIENT,
                    new ProviderCredentials(
                            new AliyunCredentials(
                                    baseEndpoint + "/aliyun", aliyun.id, aliyun.value()),
                            new TencentCredentials(
                                    baseEndpoint + "/tencent", tencent.id, tencent.value())));
        }

        private void erase() {
            webhook.erase();
            aliyun.erase();
            tencent.erase();
            sendTimes.clear();
        }

        private boolean allowSend(Instant now) {
            while (!sendTimes.isEmpty() && !sendTimes.peekFirst().isAfter(now.minus(SEND_WINDOW)))
                sendTimes.removeFirst();
            if (sendTimes.size() >= MAX_SENDS_PER_WINDOW) return false;
            sendTimes.addLast(now);
            return true;
        }
    }

    private record StoredMessage(UUID sessionId, Instant expiresAt, Message message) {}

    private final Clock clock;
    private final SecureRandom random;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Deque<StoredMessage> messages = new ArrayDeque<>();
    private boolean closed;

    VerificationDebuggerSessions() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    VerificationDebuggerSessions(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    synchronized SessionView start(String owner, String baseEndpoint) {
        requireOpen();
        Instant now = clock.instant();
        purge(now);
        removeOwner(owner);
        while (sessions.size() >= MAX_SESSIONS) removeSession(sessions.keySet().iterator().next());

        Session session =
                new Session(
                        UUID.randomUUID(),
                        owner,
                        now.plus(SESSION_TTL),
                        baseEndpoint,
                        newSecret(null),
                        newSecret("LUMIRA_DEBUG_AKID_" + randomId()),
                        newSecret("LUMIRA_DEBUG_TENCENT_ID_" + randomId()));
        sessions.put(session.id, session);
        return session.view();
    }

    synchronized int receiveWebhook(String authorization, WebhookPayload payload) {
        if (closed) return 503;
        Instant now = clock.instant();
        purge(now);
        Session session = sessionForSecret(null, authorization, "webhook");
        if (session == null) return 401;
        if (payload == null
                || payload.to() == null
                || payload.sender() == null
                || payload.message() == null) return 400;
        if (!ALLOWED_RECIPIENT.equals(payload.to()) || !DEBUG_SENDER.equals(payload.sender()))
            return 403;
        Matcher matcher = MESSAGE_CODE.matcher(payload.message());
        if (!matcher.matches()) return 400;
        if (!session.allowSend(now)) return 429;
        appendWebhook(session, payload.to(), payload.message(), matcher.group(1), now);
        return 202;
    }

    synchronized ActiveSession authenticateCloud(
            String provider, String accessKeyId, String authorization) {
        if (closed) return null;
        purge(clock.instant());
        Session found = sessionForSecret(accessKeyId, authorization, provider);
        return found == null ? null : new ActiveSession(found.id, found.owner);
    }

    synchronized SendResult recordCloudSend(
            ActiveSession active, String provider, String recipient, String code) {
        if (closed || active == null) return SendResult.INACTIVE;
        Instant now = clock.instant();
        purge(now);
        Session session = sessions.get(active.id());
        if (session == null || !session.owner.equals(active.owner())) return SendResult.INACTIVE;
        if (!ALLOWED_RECIPIENT.equals(recipient) || code == null || !code.matches("[0-9]{6}"))
            return SendResult.INACTIVE;
        if (!session.allowSend(now)) return SendResult.RATE_LIMITED;
        String message = MESSAGE_TEMPLATE.replace("{code}", code);
        append(
                session.id,
                now,
                new Message(UUID.randomUUID(), now, recipient, message, code, provider, false));
        return SendResult.ACCEPTED;
    }

    synchronized boolean addFixtureMessage(String owner, String provider) {
        if (closed) return false;
        Instant now = clock.instant();
        purge(now);
        Session session = ownerSession(owner);
        if (session == null) return false;
        append(
                session.id,
                now,
                new Message(
                        UUID.randomUUID(),
                        now,
                        ALLOWED_RECIPIENT,
                        MESSAGE_TEMPLATE.replace("{code}", FIXTURE_CODE),
                        FIXTURE_CODE,
                        provider,
                        true));
        return true;
    }

    synchronized MessagePage messages(String owner) {
        if (closed) return new MessagePage(List.of(), "");
        Instant now = clock.instant();
        purge(now);
        Session session = ownerSession(owner);
        if (session == null) return new MessagePage(List.of(), "");
        List<Message> visible =
                messages.stream()
                        .filter(message -> message.sessionId().equals(session.id))
                        .map(StoredMessage::message)
                        .toList();
        String cursor = visible.isEmpty() ? "" : visible.get(visible.size() - 1).id().toString();
        return new MessagePage(visible, cursor);
    }

    synchronized void end(String owner) {
        removeOwner(owner);
    }

    synchronized boolean hasSession(String owner) {
        if (closed) return false;
        purge(clock.instant());
        return ownerSession(owner) != null;
    }

    synchronized SessionView current(String owner) {
        if (closed) return null;
        purge(clock.instant());
        Session session = ownerSession(owner);
        return session == null ? null : session.view();
    }

    synchronized void maintain() {
        if (!closed) purge(clock.instant());
    }

    private Session sessionForSecret(String suppliedId, String authorization, String provider) {
        Session found = null;
        for (Session session : sessions.values()) {
            Secret secret =
                    switch (provider) {
                        case "webhook" -> session.webhook;
                        case "aliyun" -> session.aliyun;
                        case "tencent" -> session.tencent;
                        default -> null;
                    };
            if (secret != null && secret.matches(suppliedId, authorization)) found = session;
        }
        return found;
    }

    private void appendWebhook(
            Session session, String recipient, String message, String code, Instant now) {
        append(
                session.id,
                now,
                new Message(UUID.randomUUID(), now, recipient, message, code, "webhook", false));
    }

    private void append(UUID sessionId, Instant now, Message message) {
        messages.addLast(new StoredMessage(sessionId, now.plus(MESSAGE_TTL), message));
        while (messages.size() > MAX_MESSAGES) messages.removeFirst();
    }

    private Session ownerSession(String owner) {
        Session found = null;
        for (Session session : sessions.values()) {
            if (session.owner.equals(owner)) found = session;
        }
        return found;
    }

    private void purge(Instant now) {
        List<UUID> expired =
                sessions.values().stream()
                        .filter(session -> !session.expiresAt.isAfter(now))
                        .map(session -> session.id)
                        .toList();
        expired.forEach(this::removeSession);
        messages.removeIf(message -> !message.expiresAt().isAfter(now));
    }

    private void removeOwner(String owner) {
        sessions.values().stream()
                .filter(session -> session.owner.equals(owner))
                .map(session -> session.id)
                .toList()
                .forEach(this::removeSession);
    }

    private void removeSession(UUID id) {
        Session removed = sessions.remove(id);
        if (removed != null) removed.erase();
        messages.removeIf(message -> message.sessionId().equals(id));
    }

    private Secret newSecret(String id) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return new Secret(id, value);
    }

    private String randomId() {
        byte[] value = new byte[12];
        random.nextBytes(value);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("debuggerClosed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        new ArrayList<>(sessions.keySet()).forEach(this::removeSession);
        messages.clear();
    }
}
