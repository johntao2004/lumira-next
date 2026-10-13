package com.lumira.plugins.alipaypay;

import java.util.Map;
import tools.jackson.databind.JsonNode;

@FunctionalInterface
interface AlipayGatewayInvoker extends AutoCloseable {
    JsonNode invoke(
            AlipaySettings.Configuration config,
            String method,
            Map<String, ?> bizContent,
            String notifyUrl);

    @Override
    default void close() {}
}
