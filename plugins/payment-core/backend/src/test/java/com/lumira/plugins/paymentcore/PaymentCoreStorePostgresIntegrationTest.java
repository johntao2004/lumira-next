package com.lumira.plugins.paymentcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.SecretStorePort;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class PaymentCoreStorePostgresIntegrationTest {
    @Test
    void persistsProviderInstantsAsPostgresTimestamptz() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String username = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        assumeTrue(url != null && !url.isBlank() && username != null && password != null);

        var dataSource = new DriverManagerDataSource(url, username, password);
        var jdbc = JdbcClient.create(dataSource);
        var store =
                new PaymentCoreStore(
                        jdbc, JsonMapper.builder().build(), mock(SecretStorePort.class));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UUID orderId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        UUID refundKey = UUID.randomUUID();
        String merchantNo =
                "LM" + orderId.toString().replace("-", "").substring(0, 30).toUpperCase();
        String tradeNo = "synthetic-trade-" + orderId.toString().replace("-", "");
        Instant expiresAt = Instant.parse("2026-10-11T02:05:00Z");
        Instant paidAt = Instant.parse("2026-10-11T02:01:00Z");
        Instant refundedAt = Instant.parse("2026-10-11T02:03:00Z");

        try {
            transaction.executeWithoutResult(
                    status -> {
                        jdbc.sql(
                                        """
                                        INSERT INTO plugin_payment_core_order(
                                            order_id,idempotency_key,request_fingerprint,provider_id,settings_version,
                                            merchant_order_no,amount_fen,subject,status,created_by)
                                        VALUES(:id,:key,:fingerprint,'alipay-pay',1,:merchant,1250,'Synthetic DB fixture','CREATING','test')
                                        """)
                                .param("id", orderId)
                                .param("key", idempotencyKey)
                                .param("fingerprint", "a".repeat(64))
                                .param("merchant", merchantNo)
                                .update();
                        store.markPrecreated(
                                orderId, tradeNo, "https://qr.alipay.com/synthetic", expiresAt);
                        store.applyNotification(
                                "alipay-pay",
                                1,
                                new PaymentGatewayPort.Notification(
                                        merchantNo,
                                        tradeNo,
                                        1250,
                                        "CNY",
                                        true,
                                        "synthetic-event",
                                        paidAt));
                        jdbc.sql(
                                        """
                                        UPDATE plugin_payment_core_order
                                           SET status='REFUND_PENDING',refund_idempotency_key=:refund_key,
                                               out_refund_no=:refund_no,refund_status='UNKNOWN',refund_reason='Synthetic fixture'
                                         WHERE order_id=:id
                                        """)
                                .param("refund_key", refundKey)
                                .param(
                                        "refund_no",
                                        "LR"
                                                + UUID.randomUUID()
                                                        .toString()
                                                        .replace("-", "")
                                                        .substring(0, 30))
                                .param("id", orderId)
                                .update();
                        store.applyRefundResult(
                                orderId,
                                new PaymentGatewayPort.RefundResult(
                                        PaymentGatewayPort.RefundState.SUCCESS,
                                        "synthetic-refund",
                                        1250,
                                        refundedAt,
                                        null));
                    });

            var row =
                    jdbc.sql(
                                    "SELECT status,expires_at,paid_at,refunded_at FROM"
                                            + " plugin_payment_core_order WHERE order_id=:id")
                            .param("id", orderId)
                            .query(
                                    (result, rowNumber) ->
                                            new PersistedInstants(
                                                    result.getString("status"),
                                                    result.getTimestamp("expires_at"),
                                                    result.getTimestamp("paid_at"),
                                                    result.getTimestamp("refunded_at")))
                            .single();
            assertEquals("REFUNDED", row.status());
            assertEquals(expiresAt, row.expiresAt().toInstant());
            assertEquals(paidAt, row.paidAt().toInstant());
            assertEquals(refundedAt, row.refundedAt().toInstant());
            assertTrue(
                    store.list(null, null, 1, 100).items().stream()
                            .anyMatch(order -> order.orderId().equals(orderId)));
            assertTrue(
                    store.list("alipay-pay", null, 1, 100).items().stream()
                            .anyMatch(order -> order.orderId().equals(orderId)));
            assertTrue(
                    store.list(null, "REFUNDED", 1, 100).items().stream()
                            .anyMatch(order -> order.orderId().equals(orderId)));
        } finally {
            jdbc.sql("DELETE FROM plugin_payment_core_order WHERE order_id=:id")
                    .param("id", orderId)
                    .update();
        }
    }

    private record PersistedInstants(
            String status,
            java.sql.Timestamp expiresAt,
            java.sql.Timestamp paidAt,
            java.sql.Timestamp refundedAt) {
        private PersistedInstants {
            assertNotNull(expiresAt);
            assertNotNull(paidAt);
            assertNotNull(refundedAt);
        }
    }
}
