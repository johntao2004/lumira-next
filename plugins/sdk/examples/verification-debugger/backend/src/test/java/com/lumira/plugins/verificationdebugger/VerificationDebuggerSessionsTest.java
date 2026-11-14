package com.lumira.plugins.verificationdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class VerificationDebuggerSessionsTest {
    @Test
    void fixedTransportProbeHasNoOtpAndDoesNotRelaxRecipientOrTextRules() {
        var sessions = new VerificationDebuggerSessions(Clock.systemUTC(), new SecureRandom());
        var session = sessions.start("qa", "http://127.0.0.1:32001");
        String auth = "Bearer " + session.bearerToken();
        var payload =
                new VerificationDebuggerSessions.WebhookPayload(
                        VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                        VerificationDebuggerSessions.DEBUG_SENDER,
                        VerificationDebuggerSessions.TRANSPORT_TEST_MESSAGE);
        assertEquals(401, sessions.receiveWebhook("Bearer invalid", payload));
        assertEquals(202, sessions.receiveWebhook(auth, payload));
        var message = sessions.messages("qa").items().getFirst();
        assertEquals("", message.code());
        assertEquals("transport-test", message.provider());
        assertEquals(
                400,
                sessions.receiveWebhook(
                        auth,
                        new VerificationDebuggerSessions.WebhookPayload(
                                payload.to(), payload.sender(), payload.message() + " extra")));
        assertEquals(
                403,
                sessions.receiveWebhook(
                        auth,
                        new VerificationDebuggerSessions.WebhookPayload(
                                "+15555550124", payload.sender(), payload.message())));
        assertEquals(
                403,
                sessions.receiveWebhook(
                        auth,
                        new VerificationDebuggerSessions.WebhookPayload(
                                payload.to(), "OtherSender", payload.message())));
        sessions.close();
        assertEquals(503, sessions.receiveWebhook(auth, payload));
    }

    @Test
    void webhookMessagesAreOwnerPrivateAndExpireWithTheirSession() {
        var clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
        var sessions = new VerificationDebuggerSessions(clock, new SecureRandom());
        var alice = sessions.start("alice", "http://127.0.0.1:32001");
        var bob = sessions.start("bob", "http://127.0.0.1:32002");

        assertEquals(
                202,
                sessions.receiveWebhook(
                        "Bearer " + alice.bearerToken(),
                        new VerificationDebuggerSessions.WebhookPayload(
                                VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                                VerificationDebuggerSessions.DEBUG_SENDER,
                                "Lumira verification code: 012345")));
        assertEquals("012345", sessions.messages("alice").items().getFirst().code());
        assertEquals(0, sessions.messages("bob").items().size());
        assertEquals(
                403,
                sessions.receiveWebhook(
                        "Bearer " + alice.bearerToken(),
                        new VerificationDebuggerSessions.WebhookPayload(
                                "+15555550124",
                                VerificationDebuggerSessions.DEBUG_SENDER,
                                "Lumira verification code: 999999")));
        assertEquals(
                400,
                sessions.receiveWebhook(
                        "Bearer " + alice.bearerToken(),
                        new VerificationDebuggerSessions.WebhookPayload(
                                VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                                VerificationDebuggerSessions.DEBUG_SENDER,
                                "invalid message")));
        for (int index = 0; index < 4; index++) {
            assertEquals(
                    202,
                    sessions.receiveWebhook(
                            "Bearer " + alice.bearerToken(),
                            new VerificationDebuggerSessions.WebhookPayload(
                                    VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                                    VerificationDebuggerSessions.DEBUG_SENDER,
                                    "Lumira verification code: 54321" + index)));
        }
        assertEquals(
                429,
                sessions.receiveWebhook(
                        "Bearer " + alice.bearerToken(),
                        new VerificationDebuggerSessions.WebhookPayload(
                                VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                                VerificationDebuggerSessions.DEBUG_SENDER,
                                "Lumira verification code: 654321")));
        assertEquals(5, sessions.messages("alice").items().size());

        clock.advance(Duration.ofMinutes(10));
        sessions.maintain();
        assertNull(sessions.current("alice"));
        assertEquals(0, sessions.messages("alice").items().size());
        assertEquals(0, sessions.messages("bob").items().size());

        var renewed = sessions.start("alice", "http://127.0.0.1:32003");
        assertNotNull(renewed.bearerToken());
        assertEquals(503, closeAndReceive(sessions, renewed));
    }

    private static int closeAndReceive(
            VerificationDebuggerSessions sessions,
            VerificationDebuggerSessions.SessionView session) {
        sessions.close();
        return sessions.receiveWebhook(
                "Bearer " + session.bearerToken(),
                new VerificationDebuggerSessions.WebhookPayload(
                        VerificationDebuggerSessions.ALLOWED_RECIPIENT,
                        VerificationDebuggerSessions.DEBUG_SENDER,
                        "Lumira verification code: 012345"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
