package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.TestSchema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PublicRegistrationIdempotencyConcurrencyTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private PublicAuthChallengeStore challenges;
    private TransactionTemplate transaction;
    private UUID challengeId;
    private UUID proofId;
    private UUID accountId;
    private String sessionDigest;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_registration_idem_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE platform_user(id uuid PRIMARY KEY)");
        jdbc.execute("CREATE TABLE platform_role(id uuid PRIMARY KEY)");
        for (String table :
                List.of("platform_public_auth_challenge", "platform_registration_completion")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        challenges = new PublicAuthChallengeStore(JdbcClient.create(dataSource));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        challengeId = UUID.randomUUID();
        proofId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_user(id) VALUES (?)", accountId);
        sessionDigest = "a".repeat(64);
        jdbc.update(
                """
                INSERT INTO platform_public_auth_challenge
                    (id,purpose,channel,session_digest,contact_digest,contact_ciphertext,code_digest,
                     state,attempts,resend_at,expires_at,proof_id,proof_expires_at)
                VALUES (?, 'REGISTRATION','EMAIL',?,?, 'ciphertext', NULL,
                        'VERIFIED',0,now(),now()+interval '10 minutes',?,now()+interval '5 minutes')
                """,
                challengeId,
                sessionDigest,
                "b".repeat(64),
                proofId);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void concurrentSameRequestIdReplaysTheCommittedReceipt() throws Exception {
        UUID requestId = UUID.randomUUID();
        String fingerprint = "c".repeat(64);
        var results =
                race(
                        () -> completeLikeService(requestId, fingerprint),
                        () -> completeLikeService(requestId, fingerprint));

        assertEquals("receipt-for-" + requestId, results.get(0));
        assertEquals(results.get(0), results.get(1));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_registration_completion", Integer.class));
        assertEquals(
                "CONSUMED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_public_auth_challenge WHERE id=?",
                        String.class,
                        challengeId));
    }

    @Test
    void differentRequestIdsCannotConsumeTheSameProofTwice() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var results =
                race(
                        () -> completeLikeService(first, "d".repeat(64)),
                        () -> completeLikeService(second, "e".repeat(64)));

        assertEquals(1, results.stream().filter(value -> value.startsWith("receipt-for-")).count());
        assertEquals(1, results.stream().filter("INVALID_PROOF"::equals).count());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_registration_completion", Integer.class));
        assertEquals(
                "CONSUMED",
                jdbc.queryForObject(
                        "SELECT state FROM platform_public_auth_challenge WHERE id=?",
                        String.class,
                        challengeId));
    }

    private String completeLikeService(UUID requestId, String fingerprint) throws Exception {
        assertTrue(challenges.completion(requestId).isEmpty());
        var preflight =
                challenges
                        .verifiedByProof(
                                proofId, sessionDigest, PublicAuthPurpose.REGISTRATION, false)
                        .orElseThrow();
        assertTrue(preflight.proofActive(OffsetDateTime.now()));
        preflightBarrier.countDown();
        if (!preflightBarrier.await(10, TimeUnit.SECONDS))
            throw new AssertionError("concurrent preflight timed out");

        return transaction.execute(
                status -> {
                    challenges.lockSessionFlow(PublicAuthPurpose.REGISTRATION, sessionDigest);
                    var prior = challenges.completion(requestId);
                    if (prior.isPresent()) return replay(prior.get(), proofId, fingerprint);

                    var locked =
                            challenges
                                    .verifiedByProof(
                                            proofId,
                                            sessionDigest,
                                            PublicAuthPurpose.REGISTRATION,
                                            true)
                                    .orElse(null);
                    if (locked == null || !locked.proofActive(OffsetDateTime.now()))
                        return "INVALID_PROOF";
                    if (!challenges.reserveCompletion(
                            requestId, sessionDigest, challengeId, proofId, fingerprint)) {
                        return challenges
                                .completion(requestId)
                                .map(completion -> replay(completion, proofId, fingerprint))
                                .orElse("INVALID_PROOF");
                    }
                    if (!challenges.consume(
                            challengeId, proofId, sessionDigest, PublicAuthPurpose.REGISTRATION))
                        return "INVALID_PROOF";
                    String receipt = "receipt-for-" + requestId;
                    if (!challenges.finishCompletion(
                            requestId, accountId, "{\"receiptId\":\"" + receipt + "\"}"))
                        throw new AssertionError("completion receipt was not saved");
                    return receipt;
                });
    }

    private static String replay(
            PublicAuthChallengeStore.Completion completion, UUID proofId, String fingerprint) {
        if (!proofId.equals(completion.proofId()) || !fingerprint.equals(completion.fingerprint()))
            throw new AssertionError("idempotency key reused with a different request");
        var matcher =
                java.util.regex.Pattern.compile("\\\"receiptId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                        .matcher(completion.receipt());
        if (!matcher.find()) throw new AssertionError("saved receipt did not contain a receiptId");
        return matcher.group(1);
    }

    private List<String> race(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        preflightBarrier = new CountDownLatch(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> a = executor.submit(first::get);
            Future<String> b = executor.submit(second::get);
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private CountDownLatch preflightBarrier;
}
