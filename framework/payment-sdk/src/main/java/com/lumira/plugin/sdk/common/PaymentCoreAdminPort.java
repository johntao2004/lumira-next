package com.lumira.plugin.sdk.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通过租约及类型契约访问共享 payment-core 管理服务。 Typed, lease-scoped administrative access to the shared
 * payment-core business service.
 */
public interface PaymentCoreAdminPort {
    /** 返回支付核心管理扩展的所属插件标识。 Returns the owner plugin ID of the payment administration extension. */
    String ownerPluginId();

    enum Operation {
        READ,
        CREATE,
        UPDATE,
        MANAGE,
        REFUND
    }

    /**
     * 服务端可信页面上下文；控制器必须传常量，不能传请求数据。 Trusted server-side page context; controllers must pass a
     * constant, never request data.
     */
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

        /**
         * 仅跨提供方的核心订单页面允许为 null。 Null only for the core-wide orders page, which intentionally spans
         * providers.
         */
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

    /**
     * 在操作者和可信页面权限范围内读取脱敏设置。 Reads redacted settings within actor and trusted-page authorization.
     */
    SettingsView settings(String actor, Page page);

    /**
     * 校验操作者及可信页面权限后更新设置，输入包含版本约束。 Authorizes actor and trusted page before updating settings with
     * input version constraints.
     */
    SettingsView updateSettings(String actor, Page page, SettingsUpdate input);

    /**
     * 按可信页面、提供方及状态分页查单，页码从 1 开始。 Lists orders by trusted page, provider and status with one-based
     * pagination.
     */
    OrderPage listOrders(
            String actor,
            Page page,
            String requestedProviderId,
            String status,
            int pageNumber,
            int size);

    /**
     * 校验操作者及页面权限后创建订单；clientIp 必须由宿主可信解析。 Creates an authorized order; clientIp must come from
     * trusted host resolution.
     */
    OrderView createOrder(String actor, Page page, CreateOrder input, String clientIp);

    /** 校验页面和提供方范围后查询指定订单。 Queries the order after checking page and provider scope. */
    OrderView queryOrder(String actor, Page page, UUID orderId);

    /**
     * 校验页面及管理权限后关闭订单并返回状态。 Closes the order under page/manage authorization and returns its state.
     */
    OrderView closeOrder(String actor, Page page, UUID orderId);

    /**
     * 校验页面及退款权限后提交退款；外部结果须按退款状态跟踪。 Submits an authorized refund; track the external result through
     * refund state.
     */
    OrderView refundOrder(String actor, Page page, UUID orderId, RefundRequest input);

    /** 校验页面与提供方范围后查询订单退款状态。 Queries refund state within page and provider scope. */
    OrderView queryRefund(String actor, Page page, UUID orderId);
}
