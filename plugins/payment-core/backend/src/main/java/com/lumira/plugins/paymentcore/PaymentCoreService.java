package com.lumira.plugins.paymentcore;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.plugin.sdk.common.PaymentCoreAdminPort;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Service
public class PaymentCoreService implements PaymentCoreAdminPort {
    static final Set<String> PROVIDERS = Set.of("wechat-pay", "alipay-pay");

    public record SettingsUpdate(
            long expectedVersion,
            Map<String, String> values,
            Map<String, String> secrets,
            Set<String> removeSecrets) {
        @Override
        public String toString() {
            return "SettingsUpdate[expectedVersion="
                    + expectedVersion
                    + ", values="
                    + (values == null ? Set.of() : values.keySet())
                    + ", secrets=REDACTED]";
        }
    }

    public record SettingsView(
            String providerId,
            boolean configured,
            long version,
            Map<String, String> values,
            Map<String, Boolean> secrets) {
        public SettingsView {
            values = Map.copyOf(values);
            secrets = Map.copyOf(secrets);
        }
    }

    public record CreateOrder(
            UUID idempotencyKey, String providerId, long amountFen, String subject) {}

    public record RefundRequest(UUID idempotencyKey, String reason) {}

    public record OrderView(
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
            String refundStatus) {
        static OrderView from(PaymentCoreStore.Order order) {
            return new OrderView(
                    order.orderId(),
                    order.merchantOrderNo(),
                    order.providerTradeNo(),
                    order.providerId(),
                    order.amountFen(),
                    order.currency(),
                    order.subject(),
                    order.status(),
                    order.codeUrl(),
                    order.expiresAt(),
                    order.paidAt(),
                    order.createdAt(),
                    order.refundedAt(),
                    order.refundStatus());
        }
    }

    public record OrderPage(List<OrderView> items, long total, int page, int size) {
        public OrderPage {
            items = List.copyOf(items);
        }
    }

    record VersionedSettings(long version, PaymentGatewayPort.Settings settings) {}

    record VerifiedNotification(
            long settingsVersion, PaymentGatewayPort.Notification notification) {}

    private static final TypeReference<LinkedHashMap<Long, LinkedHashMap<String, String>>>
            SECRET_VAULT_TYPE = new TypeReference<>() {};
    private static final int MAX_SETTING_FIELDS = 32;
    private static final int MAX_VALUE_LENGTH = 2048;
    private static final int MAX_SECRET_LENGTH = 8192;
    private static final int MAX_VALUE_JSON_BYTES = 16 * 1024;
    private static final int MAX_CALLBACK_VERSIONS = 64;

    private final PaymentCoreStore store;
    private final PluginExtensionsPort extensions;
    private final AdministrationPort administration;
    private final AuditPort audit;
    private final ObjectMapper mapper;

    public PaymentCoreService(
            PaymentCoreStore store,
            PluginExtensionsPort extensions,
            AdministrationPort administration,
            AuditPort audit,
            ObjectMapper mapper) {
        this.store = store;
        this.extensions = extensions;
        this.administration = administration;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public String ownerPluginId() {
        return "payment-core";
    }

    @Override
    public PaymentCoreAdminPort.SettingsView settings(
            String actor, PaymentCoreAdminPort.Page page) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.READ);
        String providerId = requireBoundProvider(page, null);
        var value = settingsInternal(providerId);
        return new PaymentCoreAdminPort.SettingsView(
                value.providerId(),
                value.configured(),
                value.version(),
                value.values(),
                value.secrets());
    }

    @Override
    @Transactional
    public PaymentCoreAdminPort.SettingsView updateSettings(
            String actor,
            PaymentCoreAdminPort.Page page,
            PaymentCoreAdminPort.SettingsUpdate input) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.UPDATE);
        String providerId = requireBoundProvider(page, null);
        var localInput =
                input == null
                        ? null
                        : new SettingsUpdate(
                                input.expectedVersion(),
                                input.values(),
                                input.secrets(),
                                input.removeSecrets());
        var value = updateSettingsInternal(actor, providerId, localInput);
        return new PaymentCoreAdminPort.SettingsView(
                value.providerId(),
                value.configured(),
                value.version(),
                value.values(),
                value.secrets());
    }

    @Override
    public PaymentCoreAdminPort.OrderPage listOrders(
            String actor,
            PaymentCoreAdminPort.Page page,
            String requestedProviderId,
            String status,
            int pageNumber,
            int size) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.READ);
        String providerId = page.providerId();
        if (providerId == null) {
            if (requestedProviderId != null && !requestedProviderId.isBlank())
                provider(requestedProviderId.strip());
            providerId = requestedProviderId == null ? null : requestedProviderId.strip();
        } else if (requestedProviderId != null
                && !requestedProviderId.isBlank()
                && !providerId.equals(requestedProviderId.strip())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "paymentProviderNotFound");
        }
        var value = listOrdersInternal(actor, providerId, status, pageNumber, size);
        return new PaymentCoreAdminPort.OrderPage(
                value.items().stream().map(PaymentCoreService::toSdk).toList(),
                value.total(),
                value.page(),
                value.size());
    }

    @Override
    public PaymentCoreAdminPort.OrderView createOrder(
            String actor,
            PaymentCoreAdminPort.Page page,
            PaymentCoreAdminPort.CreateOrder input,
            String clientIp) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.CREATE);
        if (input == null) throw badRequest("paymentOrderInvalid");
        String providerId = requireBoundProvider(page, input.providerId());
        var value =
                createOrderInternal(
                        actor,
                        new CreateOrder(
                                input.idempotencyKey(),
                                providerId,
                                input.amountFen(),
                                input.subject()),
                        clientIp);
        return toSdk(value);
    }

    @Override
    public PaymentCoreAdminPort.OrderView queryOrder(
            String actor, PaymentCoreAdminPort.Page page, UUID orderId) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.MANAGE);
        var order = requireOrderForPage(page, orderId);
        return toSdk(queryOrderInternal(actor, order));
    }

    @Override
    public PaymentCoreAdminPort.OrderView closeOrder(
            String actor, PaymentCoreAdminPort.Page page, UUID orderId) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.MANAGE);
        var order = requireOrderForPage(page, orderId);
        return toSdk(closeOrderInternal(actor, order));
    }

    @Override
    public PaymentCoreAdminPort.OrderView refundOrder(
            String actor,
            PaymentCoreAdminPort.Page page,
            UUID orderId,
            PaymentCoreAdminPort.RefundRequest input) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.REFUND);
        var order = requireOrderForPage(page, orderId);
        var request =
                input == null ? null : new RefundRequest(input.idempotencyKey(), input.reason());
        return toSdk(refundOrderInternal(actor, order, request));
    }

    @Override
    public PaymentCoreAdminPort.OrderView queryRefund(
            String actor, PaymentCoreAdminPort.Page page, UUID orderId) {
        requireGlobalOperation(actor, page, PaymentCoreAdminPort.Operation.REFUND);
        requireOrderForPage(page, orderId);
        return toSdk(queryRefundResult(actor, orderId, true));
    }

    private void requireGlobalOperation(
            String actor,
            PaymentCoreAdminPort.Page page,
            PaymentCoreAdminPort.Operation operation) {
        if (page == null || !page.supports(operation))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "paymentOperationUnavailable");
        administration.require(actor, page.accessPermission());
        administration.requireGlobal(actor, page.accessPermission());
        String permission = page.operationPermission(operation);
        administration.require(actor, permission);
        administration.requireGlobal(actor, permission);
    }

    private static String requireBoundProvider(
            PaymentCoreAdminPort.Page page, String requestedProvider) {
        if (page == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "paymentProviderNotFound");
        String boundProvider = page.providerId();
        if (boundProvider == null) {
            if (requestedProvider == null || requestedProvider.isBlank())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "paymentProviderNotFound");
            provider(requestedProvider);
            return requestedProvider;
        }
        provider(boundProvider);
        if (requestedProvider != null
                && !requestedProvider.isBlank()
                && !boundProvider.equals(requestedProvider))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "paymentProviderNotFound");
        return boundProvider;
    }

    private PaymentCoreStore.Order requireOrderForPage(
            PaymentCoreAdminPort.Page page, UUID orderId) {
        var order = requireOrder(orderId);
        if (page == null
                || (page.providerId() != null && !page.providerId().equals(order.providerId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "paymentOrderNotFound");
        return order;
    }

    private static PaymentCoreAdminPort.OrderView toSdk(OrderView value) {
        return new PaymentCoreAdminPort.OrderView(
                value.orderId(),
                value.merchantOrderNo(),
                value.providerTradeNo(),
                value.providerId(),
                value.amountFen(),
                value.currency(),
                value.subject(),
                value.status(),
                value.codeUrl(),
                value.expiresAt(),
                value.paidAt(),
                value.createdAt(),
                value.refundedAt(),
                value.refundStatus());
    }

    private SettingsView settingsInternal(String providerId) {
        provider(providerId);
        var current = store.currentSettings(providerId);
        var snapshot = store.settingsSnapshot(providerId, current.version());
        var names = gatewaySecretFields(providerId);
        var configuredSecrets = new LinkedHashMap<String, Boolean>();
        for (String name : names) configuredSecrets.put(name, snapshot.secrets().containsKey(name));
        boolean configured = validGatewaySettings(providerId, snapshot);
        return new SettingsView(
                providerId, configured, current.version(), current.values(), configuredSecrets);
    }

    @Transactional
    private SettingsView updateSettingsInternal(
            String actor, String providerId, SettingsUpdate input) {
        provider(providerId);
        if (input == null || input.expectedVersion() < 0)
            throw badRequest("paymentSettingsInvalid");
        var current = store.currentSettings(providerId);
        if (current.version() != input.expectedVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        Set<String> allowedSecrets = gatewaySecretFields(providerId);
        var values = cleanValues(input.values(), allowedSecrets);
        var submittedSecrets = cleanSecrets(input.secrets(), allowedSecrets);
        var removeSecrets = cleanRemovals(input.removeSecrets(), allowedSecrets);
        var vault = store.secretVault(providerId);
        var currentSecrets =
                new LinkedHashMap<>(vault.getOrDefault(current.version(), new LinkedHashMap<>()));
        submittedSecrets.forEach(
                (key, value) -> {
                    if (!value.isBlank()) currentSecrets.put(key, value);
                });
        removeSecrets.forEach(currentSecrets::remove);
        var candidate =
                new PaymentCoreStore.SettingsSnapshot(
                        new PaymentCoreStore.SettingsVersion(
                                providerId, current.version() + 1, values),
                        currentSecrets);
        if (!validGatewaySettings(providerId, candidate))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentSettingsInvalid");
        if (jsonBytes(values) > MAX_VALUE_JSON_BYTES)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "paymentSettingsTooLarge");

        Set<Long> retainedVersions = store.referencedSettingsVersions(providerId);
        var retainedVault = new LinkedHashMap<Long, LinkedHashMap<String, String>>();
        vault.forEach(
                (version, secretMap) -> {
                    if (retainedVersions.contains(version))
                        retainedVault.put(version, new LinkedHashMap<>(secretMap));
                });
        long nextVersion =
                store.saveSettings(
                        providerId,
                        input.expectedVersion(),
                        values,
                        retainedVault,
                        currentSecrets,
                        actor);
        audit(
                actor,
                "payment.settings.update",
                UUID.nameUUIDFromBytes(
                        ("payment-settings:" + providerId).getBytes(StandardCharsets.UTF_8)));
        return settingsAfterSave(providerId, nextVersion, values, currentSecrets);
    }

    private SettingsView settingsAfterSave(
            String providerId,
            long version,
            Map<String, String> values,
            Map<String, String> secretValues) {
        var names = gatewaySecretFields(providerId);
        var configuredSecrets = new LinkedHashMap<String, Boolean>();
        for (String name : names) configuredSecrets.put(name, secretValues.containsKey(name));
        boolean configured =
                validGatewaySettings(
                        providerId,
                        new PaymentCoreStore.SettingsSnapshot(
                                new PaymentCoreStore.SettingsVersion(providerId, version, values),
                                secretValues));
        return new SettingsView(providerId, configured, version, values, configuredSecrets);
    }

    private OrderPage listOrdersInternal(
            String actor, String providerId, String status, int page, int size) {
        if (providerId != null && !providerId.isBlank()) provider(providerId);
        else providerId = null;
        if (status != null
                && !status.isBlank()
                && !Set.of(
                                "CREATING",
                                "PENDING",
                                "UNKNOWN",
                                "PAID",
                                "CLOSED",
                                "FAILED",
                                "REFUND_PENDING",
                                "REFUNDED")
                        .contains(status)) throw badRequest("paymentStatusInvalid");
        else if (status != null && status.isBlank()) status = null;
        int boundedPage = Math.max(1, Math.min(page, 1_000_000));
        int boundedSize = Math.max(1, Math.min(size, 100));
        var result = store.list(providerId, status, boundedPage, boundedSize);
        return new OrderPage(
                result.items().stream().map(OrderView::from).toList(),
                result.total(),
                boundedPage,
                boundedSize);
    }

    private OrderView createOrderInternal(String actor, CreateOrder input, String clientIp) {
        if (input == null
                || input.idempotencyKey() == null
                || input.providerId() == null
                || input.amountFen() < 1
                || input.amountFen() > 2_147_483_647L
                || input.subject() == null
                || input.subject().isBlank()
                || input.subject().indexOf(0) >= 0
                || input.subject().getBytes(StandardCharsets.UTF_8).length > 127)
            throw badRequest("paymentOrderInvalid");
        provider(input.providerId());
        String fingerprint = fingerprint(input.providerId(), input.amountFen(), input.subject());
        var existing = store.findByIdempotencyKey(input.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotencyConflict");
            return OrderView.from(existing.get());
        }
        var current = store.currentSettings(input.providerId());
        var config = store.settingsSnapshot(input.providerId(), current.version());
        if (!validGatewaySettings(input.providerId(), config))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "paymentSettingsInvalid");
        String callbackUrl = callbackUrl(config.version().values(), input.providerId());
        UUID orderId = UUID.randomUUID();
        String merchantOrderNo =
                "LM"
                        + orderId.toString()
                                .replace("-", "")
                                .substring(0, 30)
                                .toUpperCase(Locale.ROOT);
        var reservation =
                store.reserveOrder(
                        orderId,
                        input.idempotencyKey(),
                        fingerprint,
                        input.providerId(),
                        current.version(),
                        merchantOrderNo,
                        input.amountFen(),
                        input.subject(),
                        actor);
        if (!reservation.created()) return OrderView.from(reservation.order());
        PaymentCoreStore.Order order = reservation.order();
        try {
            var response =
                    gateway(
                            input.providerId(),
                            adapter ->
                                    adapter.precreate(
                                            gatewaySettings(config),
                                            new PaymentGatewayPort.OrderRequest(
                                                    order.merchantOrderNo(),
                                                    order.amountFen(),
                                                    order.subject(),
                                                    callbackUrl,
                                                    safeClientIp(clientIp))));
            if (response == null
                    || response.codeUrl() == null
                    || response.codeUrl().isBlank()
                    || response.codeUrl().length() > 2048)
                throw new IllegalStateException("paymentPrecreateInvalid");
            store.markPrecreated(
                    order.orderId(),
                    response.providerTradeNo(),
                    response.codeUrl(),
                    response.expiresAt());
        } catch (RuntimeException unavailable) {
            store.markUnknown(order.orderId(), "gatewayRequestFailed");
        }
        audit(actor, "payment.order.create", order.orderId());
        return OrderView.from(store.findOrder(order.orderId()).orElseThrow());
    }

    private OrderView queryOrderInternal(String actor, PaymentCoreStore.Order order) {
        UUID orderId = order.orderId();
        if (Set.of("CLOSED", "FAILED", "REFUNDED").contains(order.status()))
            return OrderView.from(order);
        var snapshot = store.settingsSnapshot(order.providerId(), order.settingsVersion());
        PaymentGatewayPort.TradeResult result;
        try {
            result =
                    gateway(
                            order.providerId(),
                            adapter ->
                                    adapter.query(
                                            gatewaySettings(snapshot), order.merchantOrderNo()));
        } catch (RuntimeException unavailable) {
            store.markUnknown(orderId, "gatewayRequestFailed");
            return OrderView.from(requireOrder(orderId));
        }
        if (result == null || result.state() == null)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "paymentQueryInvalid");
        store.applyTradeResult(orderId, result);
        audit(actor, "payment.order.query", orderId);
        return OrderView.from(requireOrder(orderId));
    }

    private OrderView closeOrderInternal(String actor, PaymentCoreStore.Order order) {
        UUID orderId = order.orderId();
        if (!Set.of("CREATING", "PENDING", "UNKNOWN").contains(order.status()))
            return OrderView.from(order);
        var snapshot = store.settingsSnapshot(order.providerId(), order.settingsVersion());
        try {
            gateway(
                    order.providerId(),
                    adapter -> {
                        adapter.close(gatewaySettings(snapshot), order.merchantOrderNo());
                        return Boolean.TRUE;
                    });
            store.markClosed(orderId);
        } catch (RuntimeException unavailable) {
            store.markUnknown(orderId, "gatewayRequestFailed");
        }
        audit(actor, "payment.order.close", orderId);
        return OrderView.from(requireOrder(orderId));
    }

    private OrderView refundOrderInternal(
            String actor, PaymentCoreStore.Order existingOrder, RefundRequest request) {
        UUID orderId = existingOrder.orderId();
        if (request == null
                || request.idempotencyKey() == null
                || request.reason() != null && request.reason().indexOf(0) >= 0)
            throw badRequest("paymentRefundInvalid");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank() || reason.getBytes(StandardCharsets.UTF_8).length > 80)
            throw badRequest("paymentRefundInvalid");
        var reservation = store.reserveRefund(orderId, request.idempotencyKey(), reason);
        if ("REFUNDED".equals(reservation.order().status()))
            return OrderView.from(reservation.order());
        if (!reservation.created()) return queryRefundResult(actor, orderId, false);
        var order = reservation.order();
        var snapshot = store.settingsSnapshot(order.providerId(), order.settingsVersion());
        var refund = refundRequest(order);
        try {
            var result =
                    gateway(
                            order.providerId(),
                            adapter -> adapter.refund(gatewaySettings(snapshot), refund));
            if (result == null || result.state() == null)
                throw new IllegalStateException("paymentRefundInvalid");
            store.applyRefundResult(orderId, result);
        } catch (RuntimeException unavailable) {
            store.markRefundUnknown(orderId);
        }
        audit(actor, "payment.order.refund", orderId);
        return OrderView.from(requireOrder(orderId));
    }

    private OrderView queryRefundResult(String actor, UUID orderId, boolean auditQuery) {
        var order = requireOrder(orderId);
        if ("REFUNDED".equals(order.status()) || "FAILED".equals(order.refundStatus()))
            return OrderView.from(order);
        if (!"REFUND_PENDING".equals(order.status()) || order.outRefundNo() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "paymentRefundNotFound");
        var snapshot = store.settingsSnapshot(order.providerId(), order.settingsVersion());
        PaymentGatewayPort.RefundResult result;
        try {
            result =
                    gateway(
                            order.providerId(),
                            adapter ->
                                    adapter.queryRefund(
                                            gatewaySettings(snapshot), refundRequest(order)));
        } catch (RuntimeException unavailable) {
            store.markRefundUnknown(orderId);
            return OrderView.from(requireOrder(orderId));
        }
        if (result == null || result.state() == null)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "paymentRefundQueryInvalid");
        store.applyRefundResult(orderId, result);
        if (auditQuery) audit(actor, "payment.order.refund.query", orderId);
        return OrderView.from(requireOrder(orderId));
    }

    void acceptCallback(String providerId, Map<String, String> headers, String rawBody) {
        provider(providerId);
        if (headers == null || rawBody == null || rawBody.length() > 128 * 1024)
            throw badRequest("paymentNotificationInvalid");
        var candidateVersions =
                store.callbackSettingsVersions(providerId, MAX_CALLBACK_VERSIONS + 1);
        if (candidateVersions.size() > MAX_CALLBACK_VERSIONS)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "paymentCallbackWindowExceeded");
        if (candidateVersions.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "paymentSettingsUnavailable");
        List<VersionedSettings> candidates =
                candidateVersions.stream()
                        .map(
                                version -> {
                                    var snapshot = store.settingsSnapshot(providerId, version);
                                    return new VersionedSettings(
                                            version, gatewaySettings(snapshot));
                                })
                        .toList();
        var verified =
                gateway(
                        providerId,
                        adapter -> {
                            for (var candidate : candidates) {
                                PaymentGatewayPort.Notification notification;
                                try {
                                    notification =
                                            adapter.verifyCallback(
                                                    candidate.settings(), headers, rawBody);
                                } catch (RuntimeException invalidSignatureOrPayload) {
                                    // Provider-controlled payloads and cryptographic errors stay
                                    // out of logs/responses.
                                    continue;
                                }
                                if (notification == null
                                        || notification.merchantOrderNo() == null
                                        || notification.merchantOrderNo().isBlank()) continue;
                                var orderVersion =
                                        store.findSettingsVersionByMerchantOrderNo(
                                                notification.merchantOrderNo(), providerId);
                                if (orderVersion.isPresent()
                                        && orderVersion.get() == candidate.version())
                                    return new VerifiedNotification(
                                            candidate.version(), notification);
                            }
                            return null;
                        });
        if (verified == null || verified.notification() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentNotificationInvalid");
        var notification = verified.notification();
        if (notification.merchantOrderNo() == null || notification.merchantOrderNo().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentNotificationInvalid");
        store.applyNotification(providerId, verified.settingsVersion(), notification);
    }

    void reconcile() {
        for (var order : store.reconcileCandidates(20)) {
            try {
                if ("REFUND_PENDING".equals(order.status()))
                    queryRefundResult("payment-core-reconcile", order.orderId(), false);
                else queryOrderForReconcile(order);
            } catch (RuntimeException unavailable) {
                // Each order is isolated; the next scheduled pass retries it with the same provider
                // ID.
            }
        }
    }

    private void queryOrderForReconcile(PaymentCoreStore.Order order) {
        var snapshot = store.settingsSnapshot(order.providerId(), order.settingsVersion());
        var result =
                gateway(
                        order.providerId(),
                        adapter ->
                                adapter.query(gatewaySettings(snapshot), order.merchantOrderNo()));
        if (result == null || result.state() == null) return;
        store.applyTradeResult(order.orderId(), result);
    }

    private boolean validGatewaySettings(
            String providerId, PaymentCoreStore.SettingsSnapshot snapshot) {
        try {
            callbackUrl(snapshot.version().values(), providerId);
            var result =
                    gateway(providerId, adapter -> adapter.validate(gatewaySettings(snapshot)));
            return result != null && result.valid();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private PaymentGatewayPort.Settings gatewaySettings(
            PaymentCoreStore.SettingsSnapshot snapshot) {
        return new PaymentGatewayPort.Settings(snapshot.version().values(), snapshot.secrets());
    }

    private static PaymentGatewayPort.RefundRequest refundRequest(PaymentCoreStore.Order order) {
        return new PaymentGatewayPort.RefundRequest(
                order.merchantOrderNo(),
                order.outRefundNo(),
                order.amountFen(),
                order.refundReason());
    }

    private <T> T gateway(String providerId, Function<PaymentGatewayPort, T> operation) {
        AtomicBoolean invoked = new AtomicBoolean();
        var results =
                extensions.extensions(
                        PaymentGatewayPort.class,
                        gateway -> {
                            if (!providerId.equals(gateway.id())
                                    || !invoked.compareAndSet(false, true)) return null;
                            return operation.apply(gateway);
                        });
        if (!invoked.get())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "paymentProviderUnavailable");
        return results.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Set<String> gatewaySecretFields(String providerId) {
        var names = gateway(providerId, PaymentGatewayPort::secretFields);
        if (names == null) return Set.of();
        if (names.size() > MAX_SETTING_FIELDS
                || names.stream().anyMatch(name -> !validFieldName(name)))
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "paymentProviderContractInvalid");
        return Set.copyOf(names);
    }

    private void audit(String actor, String action, UUID orderId) {
        if (audit != null) audit.append(actor, action, orderId);
    }

    private PaymentCoreStore.Order requireOrder(UUID id) {
        if (id == null) throw badRequest("paymentOrderInvalid");
        return store.findOrder(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "paymentOrderNotFound"));
    }

    private static void provider(String id) {
        if (!PROVIDERS.contains(id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "paymentProviderNotFound");
    }

    private static String callbackUrl(Map<String, String> values, String providerId) {
        String value = values.get("notifyUrl");
        try {
            URI uri = URI.create(value == null ? "" : value);
            String path = "/api/plugins/payment-core/callbacks/" + providerId;
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || !path.equals(uri.getRawPath())) throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "paymentNotifyUrlInvalid");
        }
    }

    private static String safeClientIp(String value) {
        if (value == null || value.length() > 64 || !value.matches("[0-9A-Fa-f:.%]+"))
            return "127.0.0.1";
        return value;
    }

    static Map<String, String> cleanValues(Map<String, String> input, Set<String> secretFields) {
        if (input == null || input.size() > MAX_SETTING_FIELDS)
            throw badRequest("paymentSettingsInvalid");
        var result = new LinkedHashMap<String, String>();
        input.forEach(
                (key, value) -> {
                    if (!validFieldName(key)
                            || secretFields.stream()
                                    .anyMatch(secret -> secret.equalsIgnoreCase(key))
                            || value == null
                            || value.length() > MAX_VALUE_LENGTH)
                        throw badRequest("paymentSettingsInvalid");
                    result.put(key, value);
                });
        return Map.copyOf(result);
    }

    static Map<String, String> cleanSecrets(Map<String, String> input, Set<String> secretFields) {
        if (input == null) return Map.of();
        if (input.size() > MAX_SETTING_FIELDS) throw badRequest("paymentSettingsInvalid");
        var result = new LinkedHashMap<String, String>();
        input.forEach(
                (key, value) -> {
                    if (!validFieldName(key)
                            || !secretFields.contains(key)
                            || value == null
                            || value.length() > MAX_SECRET_LENGTH)
                        throw badRequest("paymentSettingsInvalid");
                    result.put(key, value);
                });
        return Map.copyOf(result);
    }

    private static Set<String> cleanRemovals(Set<String> input, Set<String> secretFields) {
        if (input == null) return Set.of();
        if (input.size() > MAX_SETTING_FIELDS) throw badRequest("paymentSettingsInvalid");
        if (input.stream().anyMatch(key -> !validFieldName(key) || !secretFields.contains(key)))
            throw badRequest("paymentSettingsInvalid");
        return Set.copyOf(input);
    }

    private static boolean validFieldName(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}");
    }

    private int jsonBytes(Map<String, String> values) {
        try {
            return mapper.writeValueAsBytes(values).length;
        } catch (Exception invalid) {
            throw badRequest("paymentSettingsInvalid");
        }
    }

    private static String fingerprint(String providerId, long amountFen, String subject) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes =
                    digest.digest(
                            (providerId + "\n" + amountFen + "\n" + subject)
                                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
