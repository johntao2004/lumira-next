package com.lumira.plugins.paymentcore;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.plugin.sdk.common.PaymentCoreAdminPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ModuleEndpoint("payment-core")
@RestController
@RequestMapping("/api/plugins/payment-core")
public class PaymentCoreController {
    private final PaymentCoreService service;

    public PaymentCoreController(PaymentCoreService service) {
        this.service = service;
    }

    @GetMapping("/orders")
    public PaymentCoreAdminPort.OrderPage orders(
            Authentication auth,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var context = PaymentCoreAdminPort.Page.PAYMENT_CORE_ORDERS;
        if (providerId != null && !providerId.isBlank()) {
            // The core-wide page is the only route whose trusted context allows provider choice.
            providerId = providerId.strip();
        }
        return service.listOrders(auth.getName(), context, providerId, status, page, size);
    }

    @PostMapping("/orders")
    public PaymentCoreAdminPort.OrderView createOrder(
            Authentication auth,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "paymentOrderInvalid")
                    PaymentCoreAdminPort.CreateOrder input,
            HttpServletRequest request) {
        return service.createOrder(
                auth.getName(),
                PaymentCoreAdminPort.Page.PAYMENT_CORE_ORDERS,
                input,
                request.getRemoteAddr());
    }

    @PostMapping("/orders/{id}/query")
    public PaymentCoreAdminPort.OrderView queryOrder(Authentication auth, @PathVariable UUID id) {
        return service.queryOrder(
                auth.getName(), PaymentCoreAdminPort.Page.PAYMENT_CORE_ORDERS, id);
    }

    @PostMapping("/orders/{id}/close")
    public PaymentCoreAdminPort.OrderView closeOrder(Authentication auth, @PathVariable UUID id) {
        return service.closeOrder(
                auth.getName(), PaymentCoreAdminPort.Page.PAYMENT_CORE_ORDERS, id);
    }

    @PostMapping("/orders/{id}/refund")
    public PaymentCoreAdminPort.OrderView refundOrder(
            Authentication auth,
            @PathVariable UUID id,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "paymentRefundInvalid")
                    PaymentCoreAdminPort.RefundRequest input) {
        return service.refundOrder(
                auth.getName(), PaymentCoreAdminPort.Page.PAYMENT_CORE_ORDERS, id, input);
    }

    @PostMapping("/orders/{id}/refund/query")
    public PaymentCoreAdminPort.OrderView queryRefund(Authentication auth, @PathVariable UUID id) {
        return service.queryRefund(
                auth.getName(), PaymentCoreAdminPort.Page.PAYMENT_CORE_ORDERS, id);
    }

    @PostMapping("/callbacks/wechat-pay")
    @ContentPolicyExempt
    public ResponseEntity<Void> wechatCallback(
            HttpServletRequest request,
            @RequestBody
                    @BoundedBody(maxBytes = 128 * 1024, errorCode = "paymentNotificationInvalid")
                    String rawBody) {
        service.acceptCallback("wechat-pay", headers(request), rawBody);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/callbacks/alipay-pay")
    @ContentPolicyExempt
    public ResponseEntity<String> alipayCallback(
            HttpServletRequest request,
            @RequestBody
                    @BoundedBody(maxBytes = 128 * 1024, errorCode = "paymentNotificationInvalid")
                    String rawBody) {
        service.acceptCallback("alipay-pay", headers(request), rawBody);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body("success");
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        var result = new LinkedHashMap<String, String>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return result;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = request.getHeader(name);
            if (value == null) continue;
            result.put(name.toLowerCase(Locale.ROOT), value);
        }
        return Map.copyOf(result);
    }
}
