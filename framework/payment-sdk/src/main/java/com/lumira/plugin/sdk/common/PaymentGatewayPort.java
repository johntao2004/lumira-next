package com.lumira.plugin.sdk.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已安装支付渠道插件的租约范围适配契约。 Stable, lease-scoped adapter contract for an installed payment-channel
 * plugin.
 */
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

    /**
     * 非秘密值可保存为 JSON；秘密值仅为短期解密结果。 Non-secret values may be persisted as JSON. Secrets are
     * short-lived decrypted values.
     */
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

    /**
     * 仅在完成认证并校验提供方签名后返回该结果。 The adapter returns this only after authenticating and validating the
     * provider signature.
     */
    record Notification(
            String merchantOrderNo,
            String providerTradeNo,
            long amountFen,
            String currency,
            boolean success,
            String eventId,
            Instant occurredAt) {}

    /** 返回支付渠道适配器的稳定标识。 Returns the stable payment-channel adapter identifier. */
    String id();

    /** 返回支付渠道插件标识，默认与适配器标识一致。 Returns the owner plugin ID, defaulting to id(). */
    @Override
    default String ownerPluginId() {
        return id();
    }

    /** 返回支付网关服务类型标识。 Returns the payment-gateway service type identifier. */
    @Override
    default String serviceType() {
        return "payment-gateway";
    }

    /** 返回支付渠道展示名称，默认使用适配器标识。 Returns the channel display name, defaulting to id(). */
    default String displayName() {
        return id();
    }

    /**
     * 现有渠道保留其已发布的核心回调路径；新增渠道需覆写为各自字面路由。 Existing channels retain their published core callback. New
     * channels override with their own literal route.
     */
    default String callbackPath() {
        return "/api/plugins/payment-core/callbacks/" + id();
    }

    /**
     * 此适配器支持的秘密配置名，包含尚未配置的字段。 Secret setting names understood by this adapter, including fields not
     * configured yet.
     */
    default Set<String> secretFields() {
        return Set.of();
    }

    /**
     * 检查渠道配置并返回校验结果，不代替业务操作授权。 Validates channel settings and returns results, not business
     * authorization.
     */
    ValidationResult validate(Settings settings);

    /**
     * 按请求订单号向渠道预下单，返回渠道结果。 Precreates an order using the request merchant order number and returns
     * provider results.
     */
    PrecreateResult precreate(Settings settings, OrderRequest request);

    /** 按商户订单号查询渠道交易状态。 Queries provider trade state by merchant order number. */
    TradeResult query(Settings settings, String merchantOrderNo);

    /** 向渠道关闭指定商户订单；失败交由调用方处理。 Closes the merchant order at the provider; callers handle failure. */
    void close(Settings settings, String merchantOrderNo);

    /**
     * 向渠道提交退款请求并返回结果；不在数据库原子保证内。 Submits a provider refund and returns its result outside database
     * atomicity.
     */
    RefundResult refund(Settings settings, RefundRequest request);

    /** 查询指定退款请求在渠道侧的状态。 Queries provider state for the refund request. */
    RefundResult queryRefund(Settings settings, RefundRequest request);

    /**
     * 验证请求头及原始回调正文的渠道签名，验证成功才返回通知。 Verifies the provider signature over headers/raw body and
     * returns notification only on success.
     */
    Notification verifyCallback(Settings settings, Map<String, String> headers, String rawBody);
}
