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
public interface PaymentCoreAdminPort extends com.lumira.platform.common.PluginServicePort {
    /** 返回支付核心管理扩展的所属插件标识。 Returns the owner plugin ID of the payment administration extension. */
    String ownerPluginId();

    /** 返回支付核心管理服务的固定服务类型标识。 Returns the fixed service type of payment-core-admin. */
    @Override
    default String serviceType() {
        return "payment-core-admin";
    }

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
    enum Page implements PageContext {
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

    sealed interface PageContext permits Page, ProviderPage {
        String pluginId();

        String routeId();

        String providerId();

        String accessPermission();

        String operationPermission(Operation operation);

        boolean supports(Operation operation);
    }

    enum PageKind {
        ORDERS,
        SETTINGS
    }

    /**
     * 供任意渠道使用的服务端常量；权限标识仍归属对应渠道本身。 Server-side constant for any channel; permission names remain
     * owned by that channel.
     */
    record ProviderPage(String providerId, PageKind kind) implements PageContext {
        public ProviderPage {
            if (providerId == null
                    || !providerId.matches("[a-z][a-z0-9-]{0,31}")
                    || "payment-core".equals(providerId)
                    || kind == null) throw new IllegalArgumentException("paymentProviderNotFound");
        }

        public String pluginId() {
            return providerId;
        }

        public String routeId() {
            return providerId + "-" + segment();
        }

        public String accessPermission() {
            return "platform:" + providerId + ":" + segment() + "-access";
        }

        public String operationPermission(Operation operation) {
            if (!supports(operation))
                throw new IllegalArgumentException("paymentOperationUnavailable");
            return "platform:"
                    + providerId
                    + ":"
                    + segment()
                    + "-"
                    + operation.name().toLowerCase(java.util.Locale.ROOT);
        }

        public boolean supports(Operation operation) {
            return operation != null
                    && (kind == PageKind.SETTINGS
                            ? operation == Operation.READ || operation == Operation.UPDATE
                            : operation == Operation.READ
                                    || operation == Operation.CREATE
                                    || operation == Operation.MANAGE
                                    || operation == Operation.REFUND);
        }

        private String segment() {
            return kind == PageKind.SETTINGS ? "settings" : "orders";
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

    // 为既有插件保留基于 Page 的已发布二进制 API 兼容重载。
    // Additive overloads preserve the published Page-based binary API for existing plugins.

    /**
     * 以通用页面上下文读取渠道脱敏配置；仅兼容历史 Page 实现。 Reads masked channel settings using PageContext; supports
     * legacy Page implementations.
     */
    default SettingsView settings(String actor, PageContext page) {
        return settings(actor, legacy(page));
    }

    /**
     * 以通用页面上下文更新渠道配置并轮换加密字段。 Updates channel settings using PageContext and rotates encrypted
     * secrets.
     */
    default SettingsView updateSettings(String actor, PageContext page, SettingsUpdate input) {
        return updateSettings(actor, legacy(page), input);
    }

    /**
     * 以通用页面上下文分页查询当前提供方的支付订单。 Queries paged payment orders within the page provider scope using
     * PageContext.
     */
    default OrderPage listOrders(
            String actor, PageContext page, String provider, String status, int number, int size) {
        return listOrders(actor, legacy(page), provider, status, number, size);
    }

    /** 以通用页面上下文创建支付订单。 Creates a payment order within the page provider scope using PageContext. */
    default OrderView createOrder(
            String actor, PageContext page, CreateOrder input, String clientIp) {
        return createOrder(actor, legacy(page), input, clientIp);
    }

    /**
     * 以通用页面上下文按标识查询或刷新单个支付订单。 Queries or refreshes a single payment order within PageContext scope.
     */
    default OrderView queryOrder(String actor, PageContext page, UUID id) {
        return queryOrder(actor, legacy(page), id);
    }

    /** 以通用页面上下文关闭支付订单。 Closes a payment order within PageContext scope. */
    default OrderView closeOrder(String actor, PageContext page, UUID id) {
        return closeOrder(actor, legacy(page), id);
    }

    /** 以通用页面上下文提交订单退款申请。 Submits a refund request for a payment order within PageContext scope. */
    default OrderView refundOrder(String actor, PageContext page, UUID id, RefundRequest input) {
        return refundOrder(actor, legacy(page), id, input);
    }

    /** 以通用页面上下文查询订单退款状态。 Queries refund state for a payment order within PageContext scope. */
    default OrderView queryRefund(String actor, PageContext page, UUID id) {
        return queryRefund(actor, legacy(page), id);
    }

    /**
     * 接收指定支付提供方的异步回调请求；默认实现拒绝未支持的提供方。 Accepts an asynchronous callback payload for the named
     * provider; unsupported by default.
     */
    default void acceptCallback(String providerId, Map<String, String> headers, String rawBody) {
        throw new UnsupportedOperationException("paymentCallbackUnsupported");
    }

    /**
     * 将历史 PageContext 收窄为受信任的 Page 枚举；不支持自定义上下文实现。 Narrows a legacy PageContext to a trusted Page
     * enum; unsupported otherwise.
     */
    private static Page legacy(PageContext page) {
        if (page == null || page instanceof Page) return (Page) page;
        throw new UnsupportedOperationException("paymentContextUnsupported");
    }
}
