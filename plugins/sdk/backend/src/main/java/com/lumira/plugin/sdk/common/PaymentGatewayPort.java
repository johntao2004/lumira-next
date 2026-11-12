package com.lumira.plugin.sdk.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable, lease-scoped adapter contract for an installed payment-channel plugin. */
public interface PaymentGatewayPort extends com.lumira.platform.common.PluginServicePort {
    enum TradeState {
        PENDING,
        PAID,
        CLOSED,
        FAILED
    }

    enum RefundState {
        PENDING,
        SUCCESS,
        FAILED
    }

    /** Non-secret values may be persisted as JSON. Secrets are short-lived decrypted values. */
    record Settings(Map<String, String> values, Map<String, String> secrets) {
        public Settings {
            values = Map.copyOf(values == null ? Map.of() : values);
            secrets = Map.copyOf(secrets == null ? Map.of() : secrets);
        }

        @Override
        public String toString() {
            return "Settings[values=" + values.keySet() + ", secrets=REDACTED]";
        }
    }

    record ValidationResult(List<String> errors) {
        public ValidationResult {
            errors = List.copyOf(errors == null ? List.of() : errors);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    record OrderRequest(
            String merchantOrderNo,
            long amountFen,
            String subject,
            String notifyUrl,
            String clientIp) {}

    record PrecreateResult(String providerTradeNo, String codeUrl, Instant expiresAt) {}

    record TradeResult(
            TradeState state,
            String providerTradeNo,
            long amountFen,
            String currency,
            Instant paidAt,
            String message) {}

    record RefundRequest(
            String merchantOrderNo, String outRefundNo, long amountFen, String reason) {}

    record RefundResult(
            RefundState state,
            String providerRefundNo,
            long amountFen,
            Instant completedAt,
            String message) {}

    /** The adapter returns this only after authenticating and validating the provider signature. */
    record Notification(
            String merchantOrderNo,
            String providerTradeNo,
            long amountFen,
            String currency,
            boolean success,
            String eventId,
            Instant occurredAt) {}

    String id();

    @Override
    default String ownerPluginId() {
        return id();
    }

    @Override
    default String serviceType() {
        return "payment-gateway";
    }

    default String displayName() {
        return id();
    }

    /**
     * Existing channels retain their published core callback. New channels override with their own
     * literal route.
     */
    default String callbackPath() {
        return "/api/plugins/payment-core/callbacks/" + id();
    }

    /** Secret setting names understood by this adapter, including fields not configured yet. */
    default Set<String> secretFields() {
        return Set.of();
    }

    ValidationResult validate(Settings settings);

    PrecreateResult precreate(Settings settings, OrderRequest request);

    TradeResult query(Settings settings, String merchantOrderNo);

    void close(Settings settings, String merchantOrderNo);

    RefundResult refund(Settings settings, RefundRequest request);

    RefundResult queryRefund(Settings settings, RefundRequest request);

    Notification verifyCallback(Settings settings, Map<String, String> headers, String rawBody);
}
