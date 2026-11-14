package com.lumira.platform.sms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SmsConfigurationPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 仅本地合成接收器与隔离数据库模式，验证副作用不确定时禁止自动重发。 Local synthetic receiver and isolated schema verify no resend
 * after uncertain effects.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SmsDeliveryContractPostgresTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    DataSourceTransactionManager manager;
    SmsDeliveryStore store;
    AuditRepository audit;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_sms_contract_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        assertEquals(
                List.of(schema),
                jdbc.queryForList("SELECT unnest(current_schemas(false))", String.class));
        jdbc.execute(TestSchema.table("platform_sms_delivery"));
        TestSchema.indexes("platform_sms_delivery").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_audit"));
        manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        var client = JdbcClient.create(ds);
        store =
                new SmsDeliveryStore(
                        client,
                        new SecretKeys(
                                "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                                "primary"),
                        new ObjectMapper());
        audit = new AuditRepository(client);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) {
            assertTrue(schema.matches("qa_sms_contract_[0-9a-f]{32}"));
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    // -1 表示已消费完整请求后断开响应。
    // -1 means the receiver consumed the entire POST but dropped the response.
    @ParameterizedTest
    @ValueSource(ints = {-1, 302, 408, 429, 500, 503})
    void consumedRequestWithAmbiguousResultNeverAutomaticallyResends(int responseStatus)
            throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/send",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    consumed.incrementAndGet();
                    if (responseStatus != -1) exchange.sendResponseHeaders(responseStatus, -1);
                    exchange.close();
                });
        server.start();
        try {
            var settings =
                    new SmsConfigurationPort.Settings(
                            true,
                            "webhook",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/send",
                            "Lumira",
                            "Code {code}",
                            1);
            var source = mock(SmsConnectionSource.class);
            when(source.load())
                    .thenAnswer(
                            invocation ->
                                    new SmsConnection(
                                            settings,
                                            "synthetic-contract-key"
                                                    .getBytes(StandardCharsets.UTF_8)));
            UUID id = UUID.randomUUID();
            var message = new OutgoingSms("+15555550123", "Synthetic fixture only");
            assertEquals(Boolean.TRUE, tx.execute(s -> store.enqueue(id, message, 3600)));
            try (var worker =
                    new SmsDeliveryWorker(
                            store, source, new SmsGateway(new ObjectMapper()), audit, manager)) {
                var claim = tx.execute(s -> store.claim().orElseThrow());
                worker.process(claim);
                var status = store.status(id).orElseThrow();
                assertEquals("UNCERTAIN", status.state());
                assertEquals(1, status.attempts());
                assertEquals("DELIVERY_UNCERTAIN", status.lastResult());
                assertEquals(1, consumed.get());
                assertEquals(Boolean.FALSE, tx.execute(s -> store.enqueue(id, message, 3600)));
                jdbc.update(
                        "UPDATE platform_sms_delivery SET available_at=now()-interval '1 second' WHERE id=?",
                        id);
                worker.poll();
                worker.poll();
                assertEquals(Boolean.TRUE, tx.execute(s -> store.claim().isEmpty()));
                assertEquals(1, consumed.get());
                verify(source, times(1)).load();
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform_audit WHERE target_id=? AND action='sms.started'",
                                Integer.class,
                                id));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform_audit WHERE target_id=? AND action='sms.DELIVERY_UNCERTAIN'",
                                Integer.class,
                                id));
            }
        } finally {
            server.stop(0);
        }
    }
}
