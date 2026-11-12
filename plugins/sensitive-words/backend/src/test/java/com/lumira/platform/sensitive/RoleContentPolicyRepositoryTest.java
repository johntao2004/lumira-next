package com.lumira.platform.sensitive;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Current baseline schema guards against policy queries drifting from user and role storage. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RoleContentPolicyRepositoryTest {
    JdbcTemplate admin, jdbc;
    String schema;
    RoleContentPolicyRepository policies;
    UUID user = UUID.randomUUID(), role = UUID.randomUUID();

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                login = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, login, password));
        schema = "qa_policy_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        login,
                        password);
        jdbc = new JdbcTemplate(ds);
        for (String table :
                java.util.List.of(
                        "platform_user",
                        "platform_role",
                        "platform_user_role",
                        "platform_sensitive_role_policy")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        policies = new RoleContentPolicyRepository(JdbcClient.create(ds));
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash,enabled) VALUES(?,?,?,?,true)",
                user,
                "qa_policy",
                "Policy QA",
                "unused");
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,enabled) VALUES(?,?,?,true)",
                role,
                "qa_policy",
                "Policy QA");
        jdbc.update("INSERT INTO platform_user_role(user_id,role_id) VALUES(?,?)", user, role);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void freshRoleDefaultsOnAndPolicyUsesOptimisticVersion() {
        var rows = policies.forActor("qa_policy");
        assertEquals(1, rows.size());
        assertEquals("ON", rows.getFirst().mode());
        assertEquals(0, rows.getFirst().version());
        assertTrue(policies.save(role, "OFF", 0, "qa_policy"));
        assertFalse(policies.save(role, "ON", 0, "qa_policy"));
        assertEquals("OFF", policies.forActor("qa_policy").getFirst().mode());
        assertTrue(policies.save(role, "ON", 1, "qa_policy"));
        assertFalse(policies.save(role, "OFF", 1, "qa_policy"));
        assertEquals("ON", policies.forActor("qa_policy").getFirst().mode());
    }

    @Test
    void disabledRolesAndDeletedOrDisabledUsersDoNotContributePolicies() {
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", role);
        assertTrue(policies.forActor("qa_policy").isEmpty());
        jdbc.update("UPDATE platform_role SET enabled=true WHERE id=?", role);
        jdbc.update("UPDATE platform_user SET deleted_at=now() WHERE id=?", user);
        assertTrue(policies.forActor("qa_policy").isEmpty());
        jdbc.update("UPDATE platform_user SET deleted_at=null,enabled=false WHERE id=?", user);
        assertTrue(policies.forActor("qa_policy").isEmpty());
    }

    @Test
    void readOnlyBatchReusesSnapshotAcrossRequiresNewHitRecordingAndCommitsLogHitOnBlockRollback() {
        jdbc.execute(TestSchema.table("platform_content_policy_hit"));
        TestSchema.indexes("platform_content_policy_hit").forEach(jdbc::execute);
        var txManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        jdbc.getDataSource());
        var requiresNewTx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        requiresNewTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var forActorCalls = new java.util.concurrent.atomic.AtomicInteger();
        var txPolicies =
                new RoleContentPolicyRepository(JdbcClient.create(jdbc.getDataSource())) {
                    @Override
                    public java.util.List<Policy> forActor(String actor) {
                        forActorCalls.incrementAndGet();
                        return super.forActor(actor);
                    }

                    @Override
                    public void record(
                            String actor,
                            String source,
                            boolean blocked,
                            int matchCount,
                            java.util.List<UUID> matchedRoleIds) {
                        requiresNewTx.executeWithoutResult(
                                s ->
                                        super.record(
                                                actor,
                                                source,
                                                blocked,
                                                matchCount,
                                                matchedRoleIds));
                    }
                };
        var words = org.mockito.Mockito.mock(SensitiveWordRepository.class);
        org.mockito.Mockito.when(words.matcherSnapshot(org.mockito.Mockito.anyLong()))
                .thenReturn(
                        new SensitiveWordRepository.MatcherSnapshot(
                                1L,
                                java.util.List.of(
                                        new SensitiveWordRepository.ActiveEntry(
                                                "warnword", "warnword", "LOW", "LOG_ONLY"),
                                        new SensitiveWordRepository.ActiveEntry(
                                                "blockword", "blockword", "HIGH", "BLOCK"))));
        var modules = org.mockito.Mockito.mock(com.lumira.platform.modules.ModuleRuntime.class);
        org.mockito.Mockito.when(modules.isEnabled("sensitive-words")).thenReturn(true);
        var service =
                new RoleContentPolicyService(
                        txPolicies,
                        words,
                        org.mockito.Mockito.mock(SensitiveWordService.class),
                        org.mockito.Mockito.mock(
                                com.lumira.platform.common.AdministrationPort.class),
                        org.mockito.Mockito.mock(com.lumira.platform.common.AuditPort.class),
                        modules);
        var roTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        roTx.setReadOnly(true);
        var error =
                assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () ->
                                roTx.executeWithoutResult(
                                        s -> {
                                            assertFalse(
                                                    service.evaluate(
                                                                    "qa_policy",
                                                                    "first field warnword",
                                                                    "DictionaryController.create:name")
                                                            .blocked());
                                            if (service.evaluate(
                                                            "qa_policy",
                                                            "second field blockword",
                                                            "DictionaryController.create:remark")
                                                    .blocked()) {
                                                throw new org.springframework.web.server
                                                        .ResponseStatusException(
                                                        org.springframework.http.HttpStatus
                                                                .BAD_REQUEST,
                                                        "sensitiveContent");
                                            }
                                        }));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(1, forActorCalls.get());
        org.mockito.Mockito.verify(words, org.mockito.Mockito.times(1))
                .matcherSnapshot(org.mockito.Mockito.anyLong());
        org.mockito.Mockito.verify(modules, org.mockito.Mockito.times(1))
                .isEnabled("sensitive-words");
        var recordedHits = txPolicies.hits(new com.lumira.platform.common.PageRequest(1, 10, null));
        assertEquals(2, recordedHits.size());
        assertTrue(
                recordedHits.stream()
                        .anyMatch(h -> "LOG_ONLY".equals(h.action()) && h.matchCount() == 1));
        assertTrue(
                recordedHits.stream()
                        .anyMatch(h -> "BLOCK".equals(h.action()) && h.matchCount() == 1));
    }
}
