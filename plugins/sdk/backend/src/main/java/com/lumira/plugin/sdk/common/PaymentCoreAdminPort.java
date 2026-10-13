package com.lumira.plugin.sdk.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Typed, lease-scoped administrative access to the shared payment-core business service. */
public interface PaymentCoreAdminPort {
    String ownerPluginId();

    enum Operation {
        READ,
        CREATE,
        UPDATE,
        MANAGE,
        REFUND
    }

    /** Trusted server-side page context; controllers must pass a constant, never request data. */
    enum Page {
        PAYMENT_CORE_ORDERS(
                "payment-core",
                "payment-core-orders",
                null,
                "platform:payment-core:orders-access",
                Map.of(
                        Operation.READ, "platform:payment-core:orders-read",
                        Operation.CREATE, "platform:payment-core:orders-create",
                        Operation.MANAGE, "platform:payment-core:orders-manage",
                        Operation.REFUND, "platform:payment-core:orders-refund")),
        WECHAT_ORDERS(
                "wechat-pay",
                "wechat-pay-orders",
                "wechat-pay",
                "platform:wechat-pay:orders-access",
                Map.of(
                        Operation.READ, "platform:wechat-pay:orders-read",
                        Operation.CREATE, "platform:wechat-pay:orders-create",
                        Operation.MANAGE, "platform:wechat-pay:orders-manage",
                        Operation.REFUND, "platform:wechat-pay:orders-refund")),
        WECHAT_SETTINGS(
                "wechat-pay",
                "wechat-pay-settings",
                "wechat-pay",
                "platform:wechat-pay:settings-access",
                Map.of(
                        Operation.READ, "platform:wechat-pay:settings-read",
                        Operation.UPDATE, "platform:wechat-pay:settings-update")),
        ALIPAY_ORDERS(
                "alipay-pay",
                "alipay-pay-orders",
                "alipay-pay",
                "platform:alipay-pay:orders-access",
                Map.of(
                        Operation.READ, "platform:alipay-pay:orders-read",
                        Operation.CREATE, "platform:alipay-pay:orders-create",
                        Operation.MANAGE, "platform:alipay-pay:orders-manage",
                        Operation.REFUND, "platform:alipay-pay:orders-refund")),
        ALIPAY_SETTINGS(
                "alipay-pay",
                "alipay-pay-settings",
                "alipay-pay",
                "platform:alipay-pay:settings-access",
                Map.of(
                        Operation.READ, "platform:alipay-pay:settings-read",
                        Operation.UPDATE, "platform:alipay-pay:settings-update"));

        private final String pluginId;
        private final String routeId;
        private final String providerId;
        private final String accessPermission;
        private final Map<Operation, String> operationPermissions;

        Page(
                String pluginId,
                String routeId,
                String providerId,
                String accessPermission,
                Map<Operation, String> operationPermissions) {
            this.pluginId = pluginId;
            this.routeId = routeId;
            this.providerId = providerId;
            this.accessPermission = accessPermission;
            this.operationPermissions = Map.copyOf(operationPermissions);
        }

        public String pluginId() {
            return pluginId;
        }

        public String routeId() {
            return routeId;
        }

        /** Null only for the core-wide orders page, which intentionally spans providers. */
        public String providerId() {
            return providerId;
        }

        public String accessPermission() {
            return accessPermission;
        }

        public String operationPermission(Operation operation) {
            String permission = operationPermissions.get(operation);
            if (permission == null)
                throw new IllegalArgumentException("paymentOperationUnavailable");
            return permission;
        }

        public boolean supports(Operation operation) {
            return operationPermissions.containsKey(operation);
        }
    }

    record SettingsUpdate(
            long expectedVersion,
            Map<String, String> values,
            Map<String, String> secrets,
            Set<String> removeSecrets) {
        public SettingsUpdate {
            values = Map.copyOf(values == null ? Map.of() : values);
            secrets = Map.copyOf(secrets == null ? Map.of() : secrets);
            removeSecrets = Set.copyOf(removeSecrets == null ? Set.of() : removeSecrets);
        }

        @Override
        public String toString() {
            return "SettingsUpdate[expectedVersion="
                    + expectedVersion
                    + ", values="
                    + values.keySet()
                    + ", secrets=REDACTED]";
        }
    }

    record SettingsView(
            String providerId,
            boolean configured,
            long version,
            Map<String, String> values,
            Map<String, Boolean> secrets) {
        public SettingsView {
            values = Map.copyOf(values == null ? Map.of() : values);
            secrets = Map.copyOf(secrets == null ? Map.of() : secrets);
        }
    }

    record CreateOrder(UUID idempotencyKey, String providerId, long amountFen, String subject) {}

    record RefundRequest(UUID idempotencyKey, String reason) {}

    record OrderView(
            UUID orderId,
            String merchantOrderNo,
            String providerTradeNo,
            String providerId,
            long amountFen,
            String currency,
            String subject,
            String status,
            String codeUrl,
            Instant expiresAt,
            Instant paidAt,
            Instant createdAt,
            Instant refundedAt,
            String refundStatus) {}

    record OrderPage(List<OrderView> items, long total, int page, int size) {
        public OrderPage {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    SettingsView settings(String actor, Page page);

    SettingsView updateSettings(String actor, Page page, SettingsUpdate input);

    OrderPage listOrders(
            String actor,
            Page page,
            String requestedProviderId,
            String status,
            int pageNumber,
            int size);

    OrderView createOrder(String actor, Page page, CreateOrder input, String clientIp);

    OrderView queryOrder(String actor, Page page, UUID orderId);

    OrderView closeOrder(String actor, Page page, UUID orderId);

    OrderView refundOrder(String actor, Page page, UUID orderId, RefundRequest input);

    OrderView queryRefund(String actor, Page page, UUID orderId);
}
