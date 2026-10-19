package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.DurableEventPublisherPort;
import com.lumira.platform.common.PlatformPageId;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PlatformEventTraceDatabaseTest {
    private static final String ACTOR = "qa-event-trace-admin";
    private static final String SOURCE = "trace-test";
    private static final String EVENT_TYPE = "trace.test";
    private static final String PAYLOAD_SECRET = "do-not-return-this-payload-7f94";

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private PlatformEventOutboxStore store;
    private PlatformEventTraceQuery query;
    private TestAdministration guard;
    private JsonMapper mapper;
    private String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_event_trace_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mapper = new JsonMapper();
        store = new PlatformEventOutboxStore(JdbcClient.create(dataSource), mapper);
        guard = new TestAdministration();
        query = new PlatformEventTraceQuery(JdbcClient.create(dataSource), mapper, guard);
        createTraceSchema();
        jdbc.execute(
                "CREATE TABLE trace_consumer_effect(event_id uuid PRIMARY KEY,effect_count integer"
                        + " NOT NULL)");
    }

    @Test
    void concurrentShardAcknowledgementsAccumulateWithoutLostUpdates() throws Exception {
        UUID business = UUID.randomUUID(), operation = UUID.randomUUID(), audit = UUID.randomUUID();
        insertAudit(audit, business, "trace.concurrent");
        recordOperation(
                operation,
                "trace.concurrent",
                "BROADCAST",
                business,
                null,
                audit,
                2,
                5,
                "EVENTS_PENDING");
        append(operation, "concurrent-a", "a");
        append(operation, "concurrent-b", "b");
        var claimA = transaction.execute(status -> store.claim().orElseThrow());
        var claimB = transaction.execute(status -> store.claim().orElseThrow());
        assertNotEquals(claimA.id(), claimB.id());
        var first = new java.util.concurrent.CompletableFuture<Integer>();
        var second = new java.util.concurrent.CompletableFuture<Integer>();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a =
                    pool.submit(
                            () -> {
                                try {
                                    transaction.executeWithoutResult(
                                            status -> {
                                                jdbc.execute("SET LOCAL lock_timeout='10s'");
                                                int pidA =
                                                        jdbc.queryForObject(
                                                                "SELECT pg_backend_pid()",
                                                                Integer.class);
                                                assertTrue(
                                                        store.deliver(
                                                                claimA,
                                                                DurableEventConsumerPort
                                                                        .ConsumptionResult.of(
                                                                        2,
                                                                        1,
                                                                        Map.of(
                                                                                "RECIPIENT_INACTIVE",
                                                                                1),
                                                                        "PARTIAL")));
                                                first.complete(pidA);
                                                int pidB =
                                                        second.orTimeout(
                                                                        5,
                                                                        java.util.concurrent
                                                                                .TimeUnit.SECONDS)
                                                                .join();
                                                long deadline =
                                                        System.nanoTime()
                                                                + java.util.concurrent.TimeUnit
                                                                        .SECONDS
                                                                        .toNanos(5);
                                                boolean blocked = false;
                                                while (System.nanoTime() < deadline) {
                                                    if (Boolean.TRUE.equals(
                                                            jdbc.queryForObject(
                                                                    "SELECT CAST(? AS integer) = ANY(pg_blocking_pids(CAST(? AS integer)))",
                                                                    Boolean.class,
                                                                    pidA,
                                                                    pidB))) {
                                                        blocked = true;
                                                        break;
                                                    }
                                                    java.util.concurrent.locks.LockSupport
                                                            .parkNanos(
                                                                    java.util.concurrent.TimeUnit
                                                                            .MILLISECONDS
                                                                            .toNanos(10));
                                                }
                                                assertTrue(
                                                        blocked,
                                                        "Second delivery must actually wait on first transaction");
                                            });
                                } catch (RuntimeException | Error failure) {
                                    first.completeExceptionally(failure);
                                    throw failure;
                                }
                            });
            var b =
                    pool.submit(
                            () -> {
                                int pidA =
                                        first.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                                .join();
                                transaction.executeWithoutResult(
                                        status -> {
                                            jdbc.execute("SET LOCAL lock_timeout='10s'");
                                            int pidB =
                                                    jdbc.queryForObject(
                                                            "SELECT pg_backend_pid()",
                                                            Integer.class);
                                            assertNotEquals(pidA, pidB);
                                            second.complete(pidB);
                                            assertTrue(
                                                    store.deliver(
                                                            claimB,
                                                            DurableEventConsumerPort
                                                                    .ConsumptionResult.of(
                                                                    3,
                                                                    1,
                                                                    Map.of("DUPLICATE", 2),
                                                                    "PARTIAL")));
                                        });
                            });
            a.get(20, java.util.concurrent.TimeUnit.SECONDS);
            b.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        var result = query.operation(ACTOR, audit, 1, 20).operation();
        assertEquals(2, result.completedEventCount());
        assertTrue(result.result().known());
        assertEquals(2, result.result().inboxCreated());
        assertEquals(3, result.result().inboxSkipped());
        assertEquals(
                Map.of("RECIPIENT_INACTIVE", 1, "DUPLICATE", 2), result.result().skippedByReason());
        assertEquals("PARTIAL", result.outcomeCode());
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void broadcastShardsAggregateByExactOperationAndPaginateWithoutPayload() {
        UUID broadcastId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        insertAudit(auditId, broadcastId, "notification.broadcast");
        recordOperation(
                operationId,
                "notification.broadcast",
                "BROADCAST",
                broadcastId,
                null,
                auditId,
                3,
                3,
                "EVENTS_PENDING");

        List<UUID> eventIds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID eventId = UUID.randomUUID();
            eventIds.add(eventId);
            assertTrue(
                    store.publish(
                            eventId,
                            "notifications",
                            "notification.broadcast.requested",
                            "broadcast:" + broadcastId + ":" + index,
                            "{\"broadcastId\":\""
                                    + broadcastId
                                    + "\",\"chunk\":"
                                    + index
                                    + ",\"body\":\""
                                    + PAYLOAD_SECRET
                                    + "\"}",
                            operationId));
        }
        Map<UUID, DurableEventConsumerPort.ConsumptionResult> results =
                Map.of(
                        eventIds.get(0),
                                DurableEventConsumerPort.ConsumptionResult.of(
                                        1, 1, Map.of(), "DELIVERED"),
                        eventIds.get(1),
                                DurableEventConsumerPort.ConsumptionResult.of(
                                        1, 1, Map.of(), "DELIVERED"),
                        eventIds.get(2),
                                DurableEventConsumerPort.ConsumptionResult.of(
                                        1, 0, Map.of("RECIPIENT_INACTIVE", 1), "PARTIAL"));
        for (int index = 0; index < eventIds.size(); index++) {
            var claim = store.claim().orElseThrow();
            assertTrue(eventIds.contains(claim.id()));
            assertTrue(store.deliver(claim, results.get(claim.id())));
        }

        var all = query.operation(ACTOR, auditId, 1, 100);
        assertEquals(3, all.total());
        assertEquals(3, all.items().size());
        assertEquals(operationId, all.operation().operationId());
        assertEquals(auditId, all.operation().auditOperationId());
        assertEquals("BROADCAST", all.operation().businessType());
        assertEquals(broadcastId, all.operation().businessId());
        assertEquals("PARTIAL", all.operation().outcomeCode());
        assertTrue(all.operation().result().known());
        assertEquals(3, all.operation().result().expectedInboxCount());
        assertEquals(2, all.operation().result().inboxCreated());
        assertEquals(1, all.operation().result().inboxSkipped());
        assertEquals(Map.of("RECIPIENT_INACTIVE", 1), all.operation().result().skippedByReason());
        assertTrue(
                all.items().stream()
                        .allMatch(item -> item.event().operationId().equals(operationId)));
        assertTrue(all.items().stream().allMatch(item -> item.event().result().known()));

        var firstPage = query.operation(ACTOR, auditId, 1, 2);
        var secondPage = query.operation(ACTOR, auditId, 2, 2);
        assertEquals(3, firstPage.total());
        assertEquals(2, firstPage.items().size());
        assertEquals(1, secondPage.items().size());
        List<UUID> pagedIds = new ArrayList<>();
        firstPage.items().forEach(item -> pagedIds.add(item.event().id()));
        secondPage.items().forEach(item -> pagedIds.add(item.event().id()));
        assertEquals(all.items().stream().map(item -> item.event().id()).toList(), pagedIds);
        assertEquals(3, new HashSet<>(pagedIds).size());

        UUID eventId = all.items().getFirst().event().id();
        var eventTrace = query.event(ACTOR, eventId, 1, 20);
        assertEquals(operationId, eventTrace.event().operationId());
        assertEquals(operationId, eventTrace.operation().operationId());
        assertEquals(auditId, eventTrace.operation().auditOperationId());
        String json = mapper.writeValueAsString(eventTrace);
        assertFalse(json.contains(PAYLOAD_SECRET));
        assertFalse(json.contains("payload"));
        assertFalse(json.contains("lease_token"));

        assertTrue(
                guard.globalPermissions.contains(PlatformPageId.EVENT_OUTBOX.accessPermission()));
        assertTrue(guard.globalPermissions.contains(PlatformPageId.AUDIT.accessPermission()));
    }

    @Test
    void feedbackVersionsUseExactAuditIdsAndMissingHistoryIsNeverGuessed() {
        UUID feedbackId = UUID.randomUUID();
        UUID createOperationId = UUID.randomUUID();
        UUID createAuditId = UUID.randomUUID();
        UUID handleOperationId = UUID.randomUUID();
        UUID handleAuditId = UUID.randomUUID();
        UUID unrelatedAuditId = UUID.randomUUID();
        insertAudit(createAuditId, feedbackId, "feedback.created");
        insertAudit(handleAuditId, feedbackId, "feedback.handled");
        insertAudit(unrelatedAuditId, feedbackId, "feedback.unrelated");
        recordOperation(
                createOperationId,
                "feedback.create",
                "FEEDBACK",
                feedbackId,
                4L,
                createAuditId,
                1,
                1,
                "EVENTS_PENDING");
        recordOperation(
                handleOperationId,
                "feedback.handle",
                "FEEDBACK",
                feedbackId,
                5L,
                handleAuditId,
                1,
                1,
                "EVENTS_PENDING");

        UUID createEventId = append(createOperationId, "feedback.created", "create-v4");
        UUID handleEventId = append(handleOperationId, "feedback.handled", "handle-v5");
        deliverNow();
        deliverNow();

        var created = query.operation(ACTOR, createAuditId, 1, 20);
        var handled = query.operation(ACTOR, handleAuditId, 1, 20);
        assertEquals(4L, created.operation().businessVersion());
        assertEquals(5L, handled.operation().businessVersion());
        assertEquals(feedbackId, created.operation().businessId());
        assertEquals(feedbackId, handled.operation().businessId());
        assertEquals(createEventId, created.items().getFirst().event().id());
        assertEquals(handleEventId, handled.items().getFirst().event().id());
        assertEquals(createOperationId, created.items().getFirst().event().operationId());
        assertEquals(handleOperationId, handled.items().getFirst().event().operationId());

        var unrelated = query.operation(ACTOR, unrelatedAuditId, 1, 20);
        assertNull(unrelated.operation());
        assertTrue(unrelated.items().isEmpty());
        assertEquals("NOT_RECORDED", unrelated.historyStatus());

        UUID legacyEvent = UUID.randomUUID();
        assertTrue(store.publish(legacyEvent, SOURCE, EVENT_TYPE, "legacy", "{\"legacy\":true}"));
        var legacyTrace = query.event(ACTOR, legacyEvent, 1, 20);
        assertEquals("NOT_RECORDED", legacyTrace.historyStatus());
        assertNull(legacyTrace.operation());
        assertFalse(legacyTrace.event().result().known());

        assertThrows(
                ResponseStatusException.class,
                () -> query.operation(ACTOR, UUID.randomUUID(), 1, 20));
        var invalidPage =
                assertThrows(
                        ResponseStatusException.class,
                        () -> query.event(ACTOR, legacyEvent, 0, 20));
        assertEquals(
                HttpStatus.BAD_REQUEST, HttpStatus.valueOf(invalidPage.getStatusCode().value()));
    }

    @Test
    void failedConsumerEffectsRollbackBeforeRetryAndSuccessfulInboxResultIsDurable() {
        UUID businessId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        insertAudit(auditId, businessId, "trace.retry");
        recordOperation(
                operationId,
                "trace.retry",
                "FEEDBACK",
                businessId,
                1L,
                auditId,
                1,
                1,
                "EVENTS_PENDING");
        UUID eventId = append(operationId, "retry", "retry-payload");
        AtomicInteger calls = new AtomicInteger();
        var consumer =
                new DurableEventConsumerPort() {
                    @Override
                    public String sourceType() {
                        return SOURCE;
                    }

                    @Override
                    public String eventType() {
                        return EVENT_TYPE;
                    }

                    @Override
                    public void consume(Event event) {
                        jdbc.update(
                                "INSERT INTO trace_consumer_effect(event_id,effect_count)"
                                        + " VALUES(?,1) ON CONFLICT(event_id) DO UPDATE SET"
                                        + " effect_count=trace_consumer_effect.effect_count+1",
                                event.id());
                        if (calls.getAndIncrement() == 0)
                            throw new IllegalStateException("synthetic failure");
                    }

                    @Override
                    public ConsumptionResult consumeWithResult(Event event) {
                        consume(event);
                        return ConsumptionResult.of(1, 1, Map.of(), "DELIVERED");
                    }
                };
        var worker = worker(consumer);
        try {
            worker.process(store.claim().orElseThrow());
            assertEquals(0, effectCount(eventId), "consumer's first transaction must roll back");
            assertEquals("PENDING", status(eventId));
            assertEquals(1, attempts(eventId));
            assertEquals("CONSUMER_FAILED", lastError(eventId));
            jdbc.update("UPDATE platform_event_outbox SET available_at=now() WHERE id=?", eventId);
            worker.process(store.claim().orElseThrow());
        } finally {
            worker.close();
        }

        assertEquals(1, effectCount(eventId));
        assertEquals("DELIVERED", status(eventId));
        var eventTrace = query.event(ACTOR, eventId, 1, 20);
        assertEquals("AVAILABLE", eventTrace.historyStatus());
        assertEquals(
                List.of("CLAIMED", "FAILED", "CLAIMED", "SUCCEEDED"),
                eventTrace.history().items().stream()
                        .map(PlatformEventTraceQuery.HistoryEntry::phase)
                        .toList());
        assertEquals(1, eventTrace.history().items().getLast().result().inboxCreated());
        var operation = query.operation(ACTOR, auditId, 1, 20).operation();
        assertTrue(operation.result().known());
        assertEquals(1, operation.result().inboxCreated());
        assertEquals("DELIVERED", operation.outcomeCode());
    }

    @Test
    void oneDeadLetterKeepsTheWholeOperationInAttentionUntilApprovedReplaySucceeds() {
        UUID businessId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        insertAudit(auditId, businessId, "trace.shards");
        recordOperation(
                operationId,
                "trace.shards",
                "BROADCAST",
                businessId,
                null,
                auditId,
                2,
                2,
                "EVENTS_PENDING");
        UUID failedEvent = append(operationId, "dead-letter-shard", "dead-letter-body");
        UUID liveEvent = append(operationId, "live-shard", "live-body");
        jdbc.update(
                "UPDATE platform_event_outbox SET available_at=now()+interval '1 day' WHERE id=?",
                liveEvent);

        for (int attempt = 1; attempt <= PlatformEventOutboxStore.MAX_ATTEMPTS; attempt++) {
            var claim = store.claim().orElseThrow();
            assertEquals(failedEvent, claim.id());
            assertTrue(store.fail(claim, "consumer failed"));
            assertEquals(attempt < 8 ? "PENDING" : "DEAD_LETTER", status(failedEvent));
            if (attempt < 8)
                jdbc.update(
                        "UPDATE platform_event_outbox SET available_at=now() WHERE id=?",
                        failedEvent);
        }

        jdbc.update("UPDATE platform_event_outbox SET available_at=now() WHERE id=?", liveEvent);
        var liveClaim = store.claim().orElseThrow();
        assertEquals(liveEvent, liveClaim.id());
        assertTrue(
                store.deliver(
                        liveClaim,
                        DurableEventConsumerPort.ConsumptionResult.of(
                                1, 1, Map.of(), "DELIVERED")));

        var attention = query.operation(ACTOR, auditId, 1, 20);
        assertEquals("DEAD_LETTER", attention.operation().outcomeCode());
        assertFalse(attention.operation().result().known());
        assertEquals(1, attention.operation().completedEventCount());

        PlatformEventReplayApprovalRepository approvals =
                new PlatformEventReplayApprovalRepository(JdbcClient.create(jdbc.getDataSource()));
        TestAudit audit = new TestAudit();
        var admin = new PlatformEventOutboxAdminService(guard, store, approvals, audit);
        var missingReason =
                assertThrows(
                        ResponseStatusException.class,
                        () -> admin.replay(failedEvent, ACTOR, "  "));
        assertEquals(
                HttpStatus.BAD_REQUEST, HttpStatus.valueOf(missingReason.getStatusCode().value()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_replay_approval", Integer.class));

        var replay = admin.replay(failedEvent, ACTOR, "Restore after consumer rollout");
        assertEquals("PENDING", replay.status());
        assertEquals(0, replay.attempts());
        assertEquals(1, replay.generation());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_replay_approval WHERE event_id=? AND"
                                + " approved_by=? AND reason=?",
                        Integer.class,
                        failedEvent,
                        ACTOR,
                        "Restore after consumer rollout"));
        UUID replayAuditId = audit.lastId;
        assertNotNull(replayAuditId);
        assertEquals(
                replayAuditId,
                jdbc.queryForObject(
                        "SELECT id FROM platform_event_replay_approval WHERE event_id=?",
                        UUID.class,
                        failedEvent));
        assertEquals(
                replayAuditId,
                jdbc.queryForObject(
                        "SELECT id FROM platform_audit WHERE id=?", UUID.class, replayAuditId));
        var replayTrace = query.operation(ACTOR, replayAuditId, 1, 20);
        assertEquals("event.replay", replayTrace.operation().operationType());
        assertEquals(1, replayTrace.total());
        assertEquals(failedEvent, replayTrace.items().getFirst().event().id());
        assertEquals(1, replayTrace.operation().expectedEventCount());
        assertEquals(0, replayTrace.operation().completedEventCount());
        assertEquals("EVENTS_PENDING", replayTrace.operation().outcomeCode());
        assertFalse(replayTrace.operation().result().known());
        var replayEmptyPage = query.operation(ACTOR, replayAuditId, 2, 20);
        assertTrue(replayEmptyPage.items().isEmpty());
        assertEquals(failedEvent, replayEmptyPage.operation().businessId());

        var replayClaim = store.claim().orElseThrow();
        assertEquals(failedEvent, replayClaim.id());
        assertEquals(1, replayClaim.generation());
        var consumer =
                new DurableEventConsumerPort() {
                    @Override
                    public String sourceType() {
                        return SOURCE;
                    }

                    @Override
                    public String eventType() {
                        return EVENT_TYPE;
                    }

                    @Override
                    public void consume(Event event) {}

                    @Override
                    public ConsumptionResult consumeWithResult(Event event) {
                        return ConsumptionResult.of(1, 1, Map.of(), "DELIVERED");
                    }
                };
        var worker = worker(consumer);
        try {
            worker.process(replayClaim);
        } finally {
            worker.close();
        }

        var completed = query.operation(ACTOR, auditId, 1, 20);
        assertEquals("DELIVERED", completed.operation().outcomeCode());
        assertTrue(completed.operation().result().known());
        assertEquals(2, completed.operation().result().inboxCreated());
        var completedReplay = query.operation(ACTOR, replayAuditId, 1, 20);
        assertEquals(1, completedReplay.operation().expectedEventCount());
        assertEquals(1, completedReplay.operation().completedEventCount());
        assertTrue(completedReplay.operation().result().known());
        assertEquals(1, completedReplay.operation().result().inboxCreated());
        var failedTrace = query.event(ACTOR, failedEvent, 1, 100);
        assertEquals(1, failedTrace.event().attempts(), "a replay starts a new attempt generation");
        assertEquals(1, failedTrace.event().generation());
        assertEquals(
                8,
                failedTrace.history().items().stream()
                        .filter(entry -> entry.phase().equals("FAILED") && entry.generation() == 0)
                        .count());
        assertTrue(
                failedTrace.history().items().stream()
                        .anyMatch(
                                entry ->
                                        entry.phase().equals("REPLAY_APPROVED")
                                                && entry.actor().equals(ACTOR)
                                                && entry.reason()
                                                        .equals("Restore after consumer rollout")
                                                && entry.auditOperationId().equals(replayAuditId)));
    }

    @Test
    void expiredLeaseFencesOldTokenAndNoConsumerHistoryPaginatesWithoutBurningAttempts() {
        UUID leasedEvent = append(null, "lease-expired", "lease-payload");
        var oldClaim = store.claim().orElseThrow();
        assertEquals(leasedEvent, oldClaim.id());
        jdbc.update(
                "UPDATE platform_event_outbox SET lease_until=clock_timestamp()-interval '1 second'"
                        + " WHERE id=?",
                leasedEvent);
        assertEquals(1, store.recoverExpired().size());
        assertFalse(
                store.deliver(
                        oldClaim,
                        DurableEventConsumerPort.ConsumptionResult.of(
                                1, 1, Map.of(), "DELIVERED")));
        assertFalse(store.fail(oldClaim, "stale failure"));
        var freshClaim = store.claim().orElseThrow();
        assertEquals(leasedEvent, freshClaim.id());
        assertTrue(
                store.deliver(
                        freshClaim,
                        DurableEventConsumerPort.ConsumptionResult.of(
                                1, 1, Map.of(), "DELIVERED")));
        var leaseTrace = query.event(ACTOR, leasedEvent, 1, 20);
        assertEquals(
                List.of("CLAIMED", "LEASE_RECOVERED", "CLAIMED", "SUCCEEDED"),
                leaseTrace.history().items().stream()
                        .map(PlatformEventTraceQuery.HistoryEntry::phase)
                        .toList());

        UUID noConsumerEvent = append(null, "no-consumer", "no-consumer-payload");
        for (int attempt = 0; attempt < 13; attempt++) {
            var claim = store.claim().orElseThrow();
            assertEquals(noConsumerEvent, claim.id());
            assertTrue(store.releaseWithoutConsumer(claim));
            jdbc.update(
                    "UPDATE platform_event_outbox SET available_at=now() WHERE id=?",
                    noConsumerEvent);
        }
        assertEquals(0, attempts(noConsumerEvent));
        assertEquals("PENDING", status(noConsumerEvent));
        var pageOne = query.event(ACTOR, noConsumerEvent, 1, 10).history();
        var pageTwo = query.event(ACTOR, noConsumerEvent, 2, 10).history();
        var pageThree = query.event(ACTOR, noConsumerEvent, 3, 10).history();
        assertEquals(26, pageOne.total());
        assertEquals(10, pageOne.items().size());
        assertEquals(10, pageTwo.items().size());
        assertEquals(6, pageThree.items().size());
        List<PlatformEventTraceQuery.HistoryEntry> history = new ArrayList<>();
        history.addAll(pageOne.items());
        history.addAll(pageTwo.items());
        history.addAll(pageThree.items());
        assertEquals(
                List.of("CLAIMED", "NO_CONSUMER"),
                history.subList(0, 2).stream()
                        .map(PlatformEventTraceQuery.HistoryEntry::phase)
                        .toList());
        assertTrue(
                history.stream().allMatch(entry -> entry.attempt() == 0 || entry.attempt() == 1));
        assertTrue(
                history.stream()
                        .map(PlatformEventTraceQuery.HistoryEntry::sequence)
                        .sorted()
                        .toList()
                        .equals(
                                history.stream()
                                        .map(PlatformEventTraceQuery.HistoryEntry::sequence)
                                        .toList()));
        assertEquals(
                26,
                history.stream()
                        .map(PlatformEventTraceQuery.HistoryEntry::sequence)
                        .distinct()
                        .count());
    }

    @Test
    void retentionLeavesAnExpiredOperationTombstoneButRemovesEventHistory() {
        UUID businessId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        insertAudit(auditId, businessId, "trace.retention");
        recordOperation(
                operationId,
                "trace.retention",
                "FEEDBACK",
                businessId,
                2L,
                auditId,
                1,
                1,
                "EVENTS_PENDING");
        UUID eventId = append(operationId, "retention", "retention-payload");
        var claim = store.claim().orElseThrow();
        assertTrue(
                store.deliver(
                        claim,
                        DurableEventConsumerPort.ConsumptionResult.of(
                                1, 1, Map.of(), "DELIVERED")));
        jdbc.update(
                "UPDATE platform_event_outbox SET updated_at=now()-interval '4 days' WHERE id=?",
                eventId);

        assertEquals(1, store.purgeDelivered(Duration.ofDays(2), 20));
        var trace = query.operation(ACTOR, auditId, 1, 20);
        assertEquals("EXPIRED", trace.historyStatus());
        assertEquals("EXPIRED", trace.operation().historyStatus());
        assertEquals(1, trace.operation().result().inboxCreated());
        assertEquals(0, trace.total());
        assertTrue(trace.items().isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_trace WHERE event_id=?",
                        Integer.class,
                        eventId));
        var missing =
                assertThrows(
                        ResponseStatusException.class, () -> query.event(ACTOR, eventId, 1, 20));
        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.valueOf(missing.getStatusCode().value()));
    }

    @Test
    void authorizationIsCheckedBeforeEventAndAuditTraceDataCanBeReturned() {
        UUID businessId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        insertAudit(auditId, businessId, "trace.auth");
        recordOperation(
                operationId,
                "trace.auth",
                "FEEDBACK",
                businessId,
                1L,
                auditId,
                1,
                1,
                "EVENTS_PENDING");
        UUID eventId = append(operationId, "auth", "private-payload");
        guard.deniedPermission = PlatformPageId.EVENT_OUTBOX.accessPermission();
        var eventDenied =
                assertThrows(
                        ResponseStatusException.class, () -> query.event(ACTOR, eventId, 1, 20));
        var operationDenied =
                assertThrows(
                        ResponseStatusException.class,
                        () -> query.operation(ACTOR, auditId, 1, 20));
        assertEquals(HttpStatus.FORBIDDEN, HttpStatus.valueOf(eventDenied.getStatusCode().value()));
        assertEquals(
                HttpStatus.FORBIDDEN, HttpStatus.valueOf(operationDenied.getStatusCode().value()));
        assertTrue(
                guard.globalPermissions.contains(PlatformPageId.EVENT_OUTBOX.accessPermission()));
        guard.deniedPermission = PlatformPageId.AUDIT.accessPermission();
        var auditDenied =
                assertThrows(
                        ResponseStatusException.class,
                        () -> query.operation(ACTOR, auditId, 1, 20));
        assertEquals(HttpStatus.FORBIDDEN, HttpStatus.valueOf(auditDenied.getStatusCode().value()));
        assertTrue(guard.globalPermissions.contains(PlatformPageId.AUDIT.accessPermission()));
        guard.deniedPermission = null;
        var authorized = query.operation(ACTOR, auditId, 1, 20);
        assertEquals(eventId, authorized.items().getFirst().event().id());
    }

    private void createTraceSchema() {
        for (String table :
                List.of(
                        "platform_audit",
                        "platform_event_operation",
                        "platform_event_outbox",
                        "platform_event_trace",
                        "platform_event_replay_approval")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        for (String table :
                List.of(
                        "platform_event_outbox",
                        "platform_event_trace",
                        "platform_event_replay_approval")) {
            TestSchema.foreignKeys(table).forEach(jdbc::execute);
        }
    }

    private void recordOperation(
            UUID operationId,
            String operationType,
            String businessType,
            UUID businessId,
            Long businessVersion,
            UUID auditId,
            int eventCount,
            int inboxCount,
            String outcome) {
        store.recordOperation(
                new DurableEventPublisherPort.Operation(
                        operationId,
                        operationType,
                        businessType,
                        businessId,
                        businessVersion,
                        auditId,
                        eventCount,
                        inboxCount,
                        outcome));
    }

    private void insertAudit(UUID id, UUID target, String action) {
        jdbc.update(
                "INSERT INTO platform_audit(id,actor,action,target_id) VALUES(?,?,?,?)",
                id,
                ACTOR,
                action,
                target);
    }

    private UUID append(UUID operationId, String key, String payload) {
        UUID id = UUID.randomUUID();
        assertTrue(
                store.publish(
                        id,
                        SOURCE,
                        EVENT_TYPE,
                        key,
                        "{\"value\":\"" + payload + "\"}",
                        operationId));
        return id;
    }

    private void deliverNow() {
        var claim = store.claim().orElseThrow();
        assertTrue(
                store.deliver(
                        claim,
                        DurableEventConsumerPort.ConsumptionResult.of(
                                1, 1, Map.of(), "DELIVERED")));
    }

    private PlatformEventOutboxWorker worker(DurableEventConsumerPort consumer) {
        return new PlatformEventOutboxWorker(
                store,
                new PlatformEventConsumerRegistry(List.of(consumer), List.of()),
                transaction);
    }

    private int effectCount(UUID eventId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(effect_count),0) FROM trace_consumer_effect WHERE"
                                + " event_id=?",
                        Integer.class,
                        eventId);
        return count == null ? 0 : count;
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM platform_event_outbox WHERE id=?", String.class, eventId);
    }

    private int attempts(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT attempts FROM platform_event_outbox WHERE id=?", Integer.class, eventId);
    }

    private String lastError(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT last_error FROM platform_event_outbox WHERE id=?", String.class, eventId);
    }

    private final class TestAudit implements AuditPort {
        private UUID lastId;

        @Override
        public void append(String actor, String action, UUID targetId) {
            append(UUID.randomUUID(), actor, action, targetId);
        }

        @Override
        public void append(UUID id, String actor, String action, UUID targetId) {
            lastId = id;
            jdbc.update(
                    "INSERT INTO platform_audit(id,actor,action,target_id) VALUES(?,?,?,?)",
                    id,
                    actor,
                    action,
                    targetId);
        }
    }

    private static final class TestAdministration implements AdministrationPort {
        private final Set<String> globalPermissions = new HashSet<>();
        private boolean denied;
        private String deniedPermission;

        @Override
        public Set<String> require(String actor, String permission) {
            if (denied) throw forbidden();
            return Set.of(permission);
        }

        @Override
        public void requireUser(String actor, UUID target, String permission) {
            if (denied) throw forbidden();
        }

        @Override
        public void requireGlobal(String actor, String permission) {
            globalPermissions.add(permission);
            if (denied || permission.equals(deniedPermission)) throw forbidden();
        }

        @Override
        public boolean hasGlobal(String actor, String permission) {
            return !denied;
        }

        private static ResponseStatusException forbidden() {
            return new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
