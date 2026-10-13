package com.lumira.plugins.paymentcore;

import com.lumira.platform.common.SecretStorePort;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
class PaymentCoreStore {
    record SettingsVersion(String providerId, long version, Map<String, String> values) {
        SettingsVersion {
            values = Map.copyOf(values == null ? Map.of() : values);
        }
    }

    record SettingsSnapshot(SettingsVersion version, Map<String, String> secrets) {
        SettingsSnapshot {
            secrets = Map.copyOf(secrets == null ? Map.of() : secrets);
        }

        @Override
        public String toString() {
            return "SettingsSnapshot[version=" + version.version() + ", secrets=REDACTED]";
        }
    }

    record Order(
            UUID orderId,
            UUID idempotencyKey,
            String requestFingerprint,
            String providerId,
            long settingsVersion,
            String merchantOrderNo,
            long amountFen,
            String currency,
            String subject,
            String status,
            String codeUrl,
            String providerTradeNo,
            java.time.Instant expiresAt,
            java.time.Instant paidAt,
            UUID refundIdempotencyKey,
            String outRefundNo,
            String refundReason,
            String refundStatus,
            String providerRefundNo,
            java.time.Instant refundRequestedAt,
            java.time.Instant refundedAt,
            String lastErrorCode,
            String createdBy,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}

    record OrderPage(List<Order> items, long total, int page, int size) {
        OrderPage {
            items = List.copyOf(items);
        }
    }

    record Reservation(Order order, boolean created) {}

    record RefundReservation(Order order, boolean created) {}

    private static final String SECRET_CONTEXT_PREFIX = "plugin:payment-core/";
    private static final int MAX_SECRET_VAULT_BYTES = 48 * 1024;
    private static final TypeReference<LinkedHashMap<Long, LinkedHashMap<String, String>>>
            SECRET_VAULT_TYPE = new TypeReference<>() {};
    private static final String ORDER_COLUMNS =
            "order_id,idempotency_key,request_fingerprint,provider_id,settings_version,"
                    + "merchant_order_no,amount_fen,currency,subject,status,code_url,provider_trade_no,"
                    + "expires_at,paid_at,refund_idempotency_key,out_refund_no,refund_reason,"
                    + "refund_status,provider_refund_no,refund_requested_at,refunded_at,last_error_code,"
                    + "created_by,created_at,updated_at";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final SecretStorePort secrets;

    PaymentCoreStore(JdbcClient jdbc, ObjectMapper mapper, SecretStorePort secrets) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.secrets = secrets;
    }

    SettingsVersion currentSettings(String providerId) {
        return jdbc.sql(
                        "SELECT provider_id,current_version,values_json FROM"
                                + " plugin_payment_core_settings WHERE provider_id=:provider")
                .param("provider", providerId)
                .query(
                        (r, n) ->
                                new SettingsVersion(
                                        r.getString(1), r.getLong(2), readValues(r.getString(3))))
                .optional()
                .orElse(new SettingsVersion(providerId, 0, Map.of()));
    }

    SettingsSnapshot settingsSnapshot(String providerId, long version) {
        if (version == 0)
            return new SettingsSnapshot(new SettingsVersion(providerId, 0, Map.of()), Map.of());
        var row =
                jdbc.sql(
                                "SELECT provider_id,version,values_json FROM"
                                        + " plugin_payment_core_settings_history WHERE"
                                        + " provider_id=:provider AND version=:version")
                        .param("provider", providerId)
                        .param("version", version)
                        .query(
                                (r, n) ->
                                        new SettingsVersion(
                                                r.getString(1),
                                                r.getLong(2),
                                                readValues(r.getString(3))))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT, "paymentSettingsUnavailable"));
        var vault = readSecretVault(providerId);
        return new SettingsSnapshot(row, vault.getOrDefault(version, new LinkedHashMap<>()));
    }

    Map<Long, LinkedHashMap<String, String>> secretVault(String providerId) {
        return readSecretVault(providerId);
    }

    private Map<Long, LinkedHashMap<String, String>> readSecretVault(String providerId) {
        String context = secretContext(providerId);
        Optional<byte[]> plaintext = secrets.reveal(context);
        if (plaintext.isEmpty()) return new LinkedHashMap<>();
        byte[] bytes = plaintext.get();
        try {
            return mapper.readValue(bytes, SECRET_VAULT_TYPE);
        } catch (Exception invalid) {
            throw new IllegalStateException("Payment secret vault is unavailable");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    SecretStorePort.State secretState(String providerId) {
        return secrets.state(secretContext(providerId));
    }

    void saveSecretVault(String providerId, Map<Long, ? extends Map<String, String>> vault) {
        String context = secretContext(providerId);
        var state = secrets.state(context);
        byte[] bytes = null;
        try {
            if (!vault.isEmpty()) bytes = mapper.writeValueAsBytes(vault);
            if (bytes != null && bytes.length > MAX_SECRET_VAULT_BYTES)
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "paymentSecretsHistoryLimit");
            secrets.save(context, state.version(), bytes);
        } catch (ResponseStatusException conflict) {
            throw conflict;
        } catch (Exception failure) {
            throw new IllegalStateException("Payment secret vault could not be saved");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    @Transactional
    long saveSettings(
            String providerId,
            long expectedVersion,
            Map<String, String> values,
            Map<Long, ? extends Map<String, String>> vault,
            Map<String, String> secretsForVersion,
            String actor) {
        ensureSettingsRow(providerId);
        var current =
                jdbc.sql(
                                "SELECT current_version FROM plugin_payment_core_settings WHERE"
                                        + " provider_id=:provider FOR UPDATE")
                        .param("provider", providerId)
                        .query(Long.class)
                        .single();
        if (current != expectedVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        long next = Math.addExact(current, 1);
        var updatedVault = new LinkedHashMap<Long, LinkedHashMap<String, String>>();
        vault.forEach(
                (version, entries) -> updatedVault.put(version, new LinkedHashMap<>(entries)));
        updatedVault.put(next, new LinkedHashMap<>(secretsForVersion));
        saveSecretVault(providerId, updatedVault);
        String valuesJson = writeValues(values);
        jdbc.sql(
                        "INSERT INTO"
                                + " plugin_payment_core_settings_history(provider_id,version,values_json,created_by)"
                                + " VALUES(:provider,:version,:values,:actor)")
                .param("provider", providerId)
                .param("version", next)
                .param("values", valuesJson)
                .param("actor", actor)
                .update();
        jdbc.sql(
                        "UPDATE plugin_payment_core_settings SET"
                                + " current_version=:version,values_json=:values,updated_by=:actor,updated_at=now()"
                                + " WHERE provider_id=:provider")
                .param("provider", providerId)
                .param("version", next)
                .param("values", valuesJson)
                .param("actor", actor)
                .update();
        return next;
    }

    private void ensureSettingsRow(String providerId) {
        jdbc.sql(
                        "INSERT INTO"
                                + " plugin_payment_core_settings(provider_id,current_version,values_json,updated_by)"
                                + " VALUES(:provider,0,'{}','system') ON CONFLICT DO NOTHING")
                .param("provider", providerId)
                .update();
    }

    Optional<Order> findOrder(UUID orderId) {
        return jdbc.sql(
                        "SELECT "
                                + ORDER_COLUMNS
                                + " FROM plugin_payment_core_order WHERE order_id=:id")
                .param("id", orderId)
                .query(this::order)
                .optional();
    }

    Optional<Order> findOrderForUpdate(UUID orderId) {
        return jdbc.sql(
                        "SELECT "
                                + ORDER_COLUMNS
                                + " FROM plugin_payment_core_order WHERE order_id=:id FOR UPDATE")
                .param("id", orderId)
                .query(this::order)
                .optional();
    }

    Optional<Order> findByIdempotencyKey(UUID idempotencyKey) {
        return jdbc.sql(
                        "SELECT "
                                + ORDER_COLUMNS
                                + " FROM plugin_payment_core_order WHERE idempotency_key=:key")
                .param("key", idempotencyKey)
                .query(this::order)
                .optional();
    }

    Optional<Long> findSettingsVersionByMerchantOrderNo(String merchantOrderNo, String providerId) {
        return jdbc.sql(
                        "SELECT settings_version FROM plugin_payment_core_order WHERE"
                                + " merchant_order_no=:merchant AND provider_id=:provider")
                .param("merchant", merchantOrderNo)
                .param("provider", providerId)
                .query(Long.class)
                .optional();
    }

    @Transactional
    Reservation reserveOrder(
            UUID orderId,
            UUID idempotencyKey,
            String fingerprint,
            String providerId,
            long expectedSettingsVersion,
            String merchantOrderNo,
            long amountFen,
            String subject,
            String actor) {
        var existing = findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existingReservation(existing.get(), fingerprint);
        ensureSettingsRow(providerId);
        long currentVersion =
                jdbc.sql(
                                "SELECT current_version FROM plugin_payment_core_settings WHERE"
                                        + " provider_id=:provider FOR UPDATE")
                        .param("provider", providerId)
                        .query(Long.class)
                        .single();
        if (currentVersion != expectedSettingsVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        int inserted =
                jdbc.sql(
                                "INSERT INTO"
                                        + " plugin_payment_core_order(order_id,idempotency_key,request_fingerprint,provider_id,settings_version,merchant_order_no,amount_fen,subject,status,created_by)"
                                        + " VALUES(:id,:key,:fingerprint,:provider,:version,:merchant,:amount,:subject,'CREATING',:actor)"
                                        + " ON CONFLICT(idempotency_key) DO NOTHING")
                        .param("id", orderId)
                        .param("key", idempotencyKey)
                        .param("fingerprint", fingerprint)
                        .param("provider", providerId)
                        .param("version", currentVersion)
                        .param("merchant", merchantOrderNo)
                        .param("amount", amountFen)
                        .param("subject", subject)
                        .param("actor", actor)
                        .update();
        if (inserted == 1) return new Reservation(findOrder(orderId).orElseThrow(), true);
        var winner = findByIdempotencyKey(idempotencyKey).orElseThrow();
        return existingReservation(winner, fingerprint);
    }

    private Reservation existingReservation(Order existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotencyConflict");
        return new Reservation(existing, false);
    }

    @Transactional
    void markPrecreated(
            UUID orderId, String providerTradeNo, String codeUrl, java.time.Instant expiresAt) {
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET"
                                + " status='PENDING',provider_trade_no=:trade,code_url=:code,expires_at=:expires,last_error_code=NULL,updated_at=now()"
                                + " WHERE order_id=:id AND status='CREATING'")
                .param("id", orderId)
                .param("trade", providerTradeNo)
                .param("code", codeUrl)
                .param("expires", utcOffsetDateTime(expiresAt))
                .update();
    }

    @Transactional
    void markUnknown(UUID orderId, String code) {
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET status=CASE WHEN status IN"
                                + " ('CREATING','UNKNOWN') THEN 'UNKNOWN' ELSE status"
                                + " END,last_error_code=:code,updated_at=now() WHERE order_id=:id AND"
                                + " status NOT IN ('PAID','CLOSED','FAILED','REFUNDED')")
                .param("id", orderId)
                .param("code", code)
                .update();
    }

    Set<Long> referencedSettingsVersions(String providerId) {
        return new LinkedHashSet<>(
                jdbc.sql(
                                "SELECT DISTINCT settings_version FROM plugin_payment_core_order"
                                        + " WHERE provider_id=:provider AND (status IN"
                                        + " ('CREATING','UNKNOWN','PENDING','PAID','REFUND_PENDING') OR"
                                        + " updated_at>now()-interval '7 days') ORDER BY"
                                        + " settings_version")
                        .param("provider", providerId)
                        .query(Long.class)
                        .list());
    }

    List<Long> callbackSettingsVersions(String providerId, int limit) {
        var versions =
                new ArrayList<>(
                        jdbc.sql(
                                        "SELECT DISTINCT settings_version FROM"
                                                + " plugin_payment_core_order WHERE"
                                                + " provider_id=:provider AND (status IN"
                                                + " ('CREATING','UNKNOWN','PENDING','PAID','REFUND_PENDING')"
                                                + " OR updated_at>now()-interval '7 days') ORDER BY"
                                                + " settings_version DESC LIMIT :limit")
                                .param("provider", providerId)
                                .param("limit", limit)
                                .query(Long.class)
                                .list());
        long current = currentSettings(providerId).version();
        if (current > 0 && !versions.contains(current)) versions.add(0, current);
        return List.copyOf(versions);
    }

    @Transactional
    void applyTradeResult(
            UUID orderId, com.lumira.plugin.sdk.common.PaymentGatewayPort.TradeResult result) {
        Order order =
                findOrderForUpdate(orderId).orElseThrow(() -> notFound("paymentOrderNotFound"));
        if (result.amountFen() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "paymentQueryInvalid");
        if (result.state() == com.lumira.plugin.sdk.common.PaymentGatewayPort.TradeState.PAID) {
            requirePaymentMatches(
                    order, result.providerTradeNo(), result.amountFen(), result.currency());
            markPaid(order, result.providerTradeNo(), result.paidAt());
            return;
        }
        String nextStatus = nonPaidTradeTransition(order.status(), result.state());
        if (!nextStatus.equals(order.status()))
            updateTradeStatus(order.orderId(), nextStatus, result.providerTradeNo());
    }

    @Transactional
    void applyNotification(
            String providerId,
            long verifiedSettingsVersion,
            com.lumira.plugin.sdk.common.PaymentGatewayPort.Notification notification) {
        Order order =
                jdbc.sql(
                                "SELECT "
                                        + ORDER_COLUMNS
                                        + " FROM plugin_payment_core_order WHERE"
                                        + " merchant_order_no=:merchant FOR UPDATE")
                        .param("merchant", notification.merchantOrderNo())
                        .query(this::order)
                        .optional()
                        .orElseThrow(() -> notFound("paymentOrderNotFound"));
        requireNotificationIdentity(order, providerId, verifiedSettingsVersion);
        if (!notification.success()) return;
        requirePaymentMatches(
                order,
                notification.providerTradeNo(),
                notification.amountFen(),
                notification.currency());
        markPaid(order, notification.providerTradeNo(), notification.occurredAt());
    }

    private void markPaid(Order order, String providerTradeNo, java.time.Instant paidAt) {
        if ("REFUNDED".equals(order.status())) return;
        // A verified PAID result takes precedence over a late CLOSED/FAILED state; only refunds
        // stay terminal.
        if (order.providerTradeNo() != null
                && providerTradeNo != null
                && !order.providerTradeNo().equals(providerTradeNo))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentTradeMismatch");
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET status=CASE WHEN"
                                + " status='REFUND_PENDING' THEN 'REFUND_PENDING' ELSE 'PAID'"
                                + " END,provider_trade_no=COALESCE(provider_trade_no,:trade),paid_at=COALESCE(paid_at,:paid),last_error_code=NULL,updated_at=now()"
                                + " WHERE order_id=:id AND status<>'REFUNDED'")
                .param("id", order.orderId())
                .param("trade", providerTradeNo)
                .param("paid", utcOffsetDateTime(paidAt == null ? java.time.Instant.now() : paidAt))
                .update();
    }

    static String nonPaidTradeTransition(
            String currentStatus,
            com.lumira.plugin.sdk.common.PaymentGatewayPort.TradeState observed) {
        if (Set.of("PAID", "REFUND_PENDING", "REFUNDED").contains(currentStatus))
            return currentStatus;
        return switch (observed) {
            case PENDING ->
                    Set.of("CREATING", "UNKNOWN").contains(currentStatus)
                            ? "PENDING"
                            : currentStatus;
            case CLOSED -> "CLOSED";
            case FAILED -> "FAILED";
            case PAID -> "PAID";
        };
    }

    static void requirePaymentMatches(
            Order order, String providerTradeNo, long amountFen, String currency) {
        if (amountFen != order.amountFen()
                || !"CNY".equalsIgnoreCase(currency == null ? "" : currency)
                || providerTradeNo == null
                || providerTradeNo.isBlank()
                || providerTradeNo.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentDetailsMismatch");
        if (order.providerTradeNo() != null && !order.providerTradeNo().equals(providerTradeNo))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentTradeMismatch");
    }

    static void requireNotificationIdentity(
            Order order, String providerId, long verifiedSettingsVersion) {
        if (!order.providerId().equals(providerId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentProviderMismatch");
        if (order.settingsVersion() != verifiedSettingsVersion)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentSettingsMismatch");
    }

    private void updateTradeStatus(UUID orderId, String status, String providerTradeNo) {
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET"
                                + " status=:status,provider_trade_no=COALESCE(provider_trade_no,:trade),last_error_code=NULL,updated_at=now()"
                                + " WHERE order_id=:id AND status NOT IN"
                                + " ('PAID','REFUND_PENDING','REFUNDED')")
                .param("id", orderId)
                .param("status", status)
                .param("trade", providerTradeNo)
                .update();
    }

    @Transactional
    void markClosed(UUID orderId) {
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET"
                                + " status='CLOSED',last_error_code=NULL,updated_at=now() WHERE"
                                + " order_id=:id AND status IN ('CREATING','PENDING','UNKNOWN')")
                .param("id", orderId)
                .update();
    }

    @Transactional
    RefundReservation reserveRefund(UUID orderId, UUID key, String reason) {
        Order order =
                findOrderForUpdate(orderId).orElseThrow(() -> notFound("paymentOrderNotFound"));
        if ("REFUNDED".equals(order.status())) {
            if (key.equals(order.refundIdempotencyKey())
                    && Objects.equals(reason, order.refundReason()))
                return new RefundReservation(order, false);
            if (key.equals(order.refundIdempotencyKey()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotencyConflict");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "paymentAlreadyRefunded");
        }
        if (Set.of("REFUND_PENDING").contains(order.status())) {
            if (!key.equals(order.refundIdempotencyKey()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "refundInProgress");
            if (!Objects.equals(reason, order.refundReason()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotencyConflict");
            return new RefundReservation(order, false);
        }
        if (!"PAID".equals(order.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "paymentNotRefundable");
        if ("FAILED".equals(order.refundStatus()) && key.equals(order.refundIdempotencyKey())) {
            if (!Objects.equals(reason, order.refundReason()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotencyConflict");
            return new RefundReservation(order, false);
        }
        if (order.refundStatus() != null && !"FAILED".equals(order.refundStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "refundInProgress");
        String outRefundNo = "LR" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET"
                                + " status='REFUND_PENDING',refund_idempotency_key=:key,out_refund_no=:refund,refund_reason=:reason,refund_status='UNKNOWN',provider_refund_no=NULL,refund_requested_at=now(),refunded_at=NULL,last_error_code=NULL,updated_at=now()"
                                + " WHERE order_id=:id")
                .param("id", orderId)
                .param("key", key)
                .param("refund", outRefundNo)
                .param("reason", reason)
                .update();
        return new RefundReservation(findOrder(orderId).orElseThrow(), true);
    }

    @Transactional
    void applyRefundResult(
            UUID orderId, com.lumira.plugin.sdk.common.PaymentGatewayPort.RefundResult result) {
        Order order =
                findOrderForUpdate(orderId).orElseThrow(() -> notFound("paymentOrderNotFound"));
        if (result.state() == com.lumira.plugin.sdk.common.PaymentGatewayPort.RefundState.SUCCESS) {
            if (result.amountFen() != order.amountFen()
                    || result.providerRefundNo() == null
                    || result.providerRefundNo().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "refundAmountMismatch");
            jdbc.sql(
                            "UPDATE plugin_payment_core_order SET"
                                    + " status='REFUNDED',refund_status='SUCCESS',provider_refund_no=:provider,refunded_at=COALESCE(:completed,now()),last_error_code=NULL,updated_at=now()"
                                    + " WHERE order_id=:id AND refund_idempotency_key=:key")
                    .param("id", orderId)
                    .param("key", order.refundIdempotencyKey())
                    .param("provider", result.providerRefundNo())
                    .param("completed", utcOffsetDateTime(result.completedAt()))
                    .update();
            return;
        }
        if (result.amountFen() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "paymentRefundQueryInvalid");
        if (result.state() == com.lumira.plugin.sdk.common.PaymentGatewayPort.RefundState.FAILED) {
            jdbc.sql(
                            "UPDATE plugin_payment_core_order SET"
                                    + " status='PAID',refund_status='FAILED',provider_refund_no=:provider,last_error_code=NULL,updated_at=now()"
                                    + " WHERE order_id=:id AND status='REFUND_PENDING' AND"
                                    + " refund_idempotency_key=:key")
                    .param("id", orderId)
                    .param("key", order.refundIdempotencyKey())
                    .param("provider", result.providerRefundNo())
                    .update();
            return;
        }
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET"
                                + " status='REFUND_PENDING',refund_status='PENDING',provider_refund_no=COALESCE(:provider,provider_refund_no),last_error_code=NULL,updated_at=now()"
                                + " WHERE order_id=:id AND refund_idempotency_key=:key")
                .param("id", orderId)
                .param("key", order.refundIdempotencyKey())
                .param("provider", result.providerRefundNo())
                .update();
    }

    @Transactional
    void markRefundUnknown(UUID orderId) {
        jdbc.sql(
                        "UPDATE plugin_payment_core_order SET status=CASE WHEN"
                                + " status='REFUND_PENDING' THEN 'REFUND_PENDING' ELSE status"
                                + " END,refund_status=CASE WHEN refund_status IN ('UNKNOWN','PENDING')"
                                + " THEN 'UNKNOWN' ELSE refund_status"
                                + " END,last_error_code='gatewayRequestFailed',updated_at=now() WHERE"
                                + " order_id=:id AND status='REFUND_PENDING'")
                .param("id", orderId)
                .update();
    }

    OrderPage list(String providerId, String status, int page, int size) {
        String where =
                " WHERE (CAST(:provider AS varchar) IS NULL OR provider_id=CAST(:provider AS"
                        + " varchar)) AND (CAST(:status AS varchar) IS NULL OR status=CAST(:status AS"
                        + " varchar))";
        long total =
                jdbc.sql("SELECT count(*) FROM plugin_payment_core_order" + where)
                        .param("provider", providerId)
                        .param("status", status)
                        .query(Long.class)
                        .single();
        var items =
                jdbc.sql(
                                "SELECT "
                                        + ORDER_COLUMNS
                                        + " FROM plugin_payment_core_order"
                                        + where
                                        + " ORDER BY created_at DESC,order_id DESC LIMIT :limit"
                                        + " OFFSET :offset")
                        .param("provider", providerId)
                        .param("status", status)
                        .param("limit", size)
                        .param("offset", (long) (page - 1) * size)
                        .query(this::order)
                        .list();
        return new OrderPage(items, total, page, size);
    }

    List<Order> reconcileCandidates(int limit) {
        return jdbc.sql(
                        "SELECT "
                                + ORDER_COLUMNS
                                + " FROM plugin_payment_core_order WHERE status IN"
                                + " ('CREATING','PENDING','UNKNOWN','REFUND_PENDING') ORDER BY"
                                + " updated_at LIMIT :limit")
                .param("limit", limit)
                .query(this::order)
                .list();
    }

    private String secretContext(String providerId) {
        return SECRET_CONTEXT_PREFIX + providerId;
    }

    private Map<String, String> readValues(String json) {
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception invalid) {
            throw new IllegalStateException("Payment settings are unavailable");
        }
    }

    private String writeValues(Map<String, String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception invalid) {
            throw new IllegalStateException("Payment settings could not be saved");
        }
    }

    private Order order(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Order(
                rs.getObject("order_id", UUID.class),
                rs.getObject("idempotency_key", UUID.class),
                rs.getString("request_fingerprint").trim(),
                rs.getString("provider_id"),
                rs.getLong("settings_version"),
                rs.getString("merchant_order_no"),
                rs.getLong("amount_fen"),
                rs.getString("currency"),
                rs.getString("subject"),
                rs.getString("status"),
                rs.getString("code_url"),
                rs.getString("provider_trade_no"),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("paid_at")),
                rs.getObject("refund_idempotency_key", UUID.class),
                rs.getString("out_refund_no"),
                rs.getString("refund_reason"),
                rs.getString("refund_status"),
                rs.getString("provider_refund_no"),
                instant(rs.getTimestamp("refund_requested_at")),
                instant(rs.getTimestamp("refunded_at")),
                rs.getString("last_error_code"),
                rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private static java.time.Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime utcOffsetDateTime(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }
}
