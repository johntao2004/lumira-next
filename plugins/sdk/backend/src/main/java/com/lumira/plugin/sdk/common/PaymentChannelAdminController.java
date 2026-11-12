package com.lumira.plugin.sdk.common;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PluginExtensionsPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** Shared HTTP operations for provider-owned payment admin endpoints. */
public abstract class PaymentChannelAdminController {
    private final PluginExtensionsPort extensions;
    private final PaymentCoreAdminPort.PageContext ordersPage;
    private final PaymentCoreAdminPort.PageContext settingsPage;

    protected PaymentChannelAdminController(
            PluginExtensionsPort extensions,
            PaymentCoreAdminPort.Page ordersPage,
            PaymentCoreAdminPort.Page settingsPage) {
        this(extensions, (PaymentCoreAdminPort.PageContext) ordersPage, settingsPage);
    }

    protected PaymentChannelAdminController(
            PluginExtensionsPort extensions,
            PaymentCoreAdminPort.PageContext ordersPage,
            PaymentCoreAdminPort.PageContext settingsPage) {
        this.extensions = extensions;
        this.ordersPage = ordersPage;
        this.settingsPage = settingsPage;
    }

    @GetMapping("/settings")
    public PaymentCoreAdminPort.SettingsView settings(Authentication auth) {
        return call(service -> service.settings(auth.getName(), settingsPage));
    }

    @PutMapping("/settings")
    public PaymentCoreAdminPort.SettingsView updateSettings(
            Authentication auth,
            @RequestBody @BoundedBody(maxBytes = 32 * 1024, errorCode = "paymentSettingsInvalid")
                    PaymentCoreAdminPort.SettingsUpdate input) {
        return call(service -> service.updateSettings(auth.getName(), settingsPage, input));
    }

    @GetMapping("/orders")
    public PaymentCoreAdminPort.OrderPage orders(
            Authentication auth,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return call(
                service ->
                        service.listOrders(auth.getName(), ordersPage, null, status, page, size));
    }

    @PostMapping("/orders")
    public PaymentCoreAdminPort.OrderView createOrder(
            Authentication auth,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "paymentOrderInvalid")
                    PaymentCoreAdminPort.CreateOrder input,
            HttpServletRequest request) {
        return call(
                service ->
                        service.createOrder(
                                auth.getName(), ordersPage, input, request.getRemoteAddr()));
    }

    @PostMapping("/orders/{id}/query")
    public PaymentCoreAdminPort.OrderView queryOrder(Authentication auth, @PathVariable UUID id) {
        return call(service -> service.queryOrder(auth.getName(), ordersPage, id));
    }

    @PostMapping("/orders/{id}/close")
    public PaymentCoreAdminPort.OrderView closeOrder(Authentication auth, @PathVariable UUID id) {
        return call(service -> service.closeOrder(auth.getName(), ordersPage, id));
    }

    @PostMapping("/orders/{id}/refund")
    public PaymentCoreAdminPort.OrderView refundOrder(
            Authentication auth,
            @PathVariable UUID id,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "paymentRefundInvalid")
                    PaymentCoreAdminPort.RefundRequest input) {
        return call(service -> service.refundOrder(auth.getName(), ordersPage, id, input));
    }

    @PostMapping("/orders/{id}/refund/query")
    public PaymentCoreAdminPort.OrderView queryRefund(Authentication auth, @PathVariable UUID id) {
        return call(service -> service.queryRefund(auth.getName(), ordersPage, id));
    }

    private <T> T call(java.util.function.Function<PaymentCoreAdminPort, T> action) {
        return PaymentCoreAdminClient.call(extensions, action);
    }
}
