package com.lumira.plugin.sdk.common;

import com.lumira.platform.common.PluginExtensionsPort;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 通过宿主租约保护的扩展边界查找已启用 payment-core 服务。 Resolves the enabled payment-core service through the host's
 * leased plugin extension boundary.
 */
public final class PaymentCoreAdminClient {
    private PaymentCoreAdminClient() {}

    public static <T> T call(
            PluginExtensionsPort extensions, Function<PaymentCoreAdminPort, T> operation) {
        List<T> results =
                extensions.extensions(
                        PaymentCoreAdminPort.class,
                        service ->
                                "payment-core".equals(service.ownerPluginId())
                                        ? operation.apply(service)
                                        : null);
        List<T> matches = results.stream().filter(value -> value != null).toList();
        if (matches.size() != 1)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "paymentCoreUnavailable");
        return matches.getFirst();
    }
}
