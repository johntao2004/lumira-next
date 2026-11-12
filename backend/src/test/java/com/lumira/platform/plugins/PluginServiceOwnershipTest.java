package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.PluginServicePort;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginServiceOwnershipTest {
    private record Service(String ownerPluginId, String serviceType) implements PluginServicePort {}

    @Test
    void rejectsImpersonationAndDuplicatesBeforeAnyServiceCanBePublished() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PluginRuntime.validateOwnedServices(
                                "channel", List.of(new Service("other", "payment-gateway"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PluginRuntime.validateOwnedServices(
                                "channel",
                                List.of(
                                        new Service("channel", "payment-gateway"),
                                        new Service("channel", "payment-gateway"))));
        assertDoesNotThrow(
                () ->
                        PluginRuntime.validateOwnedServices(
                                "channel",
                                List.of(
                                        new Service("channel", "payment-gateway"),
                                        new Service("channel", "delivery-gateway"))));
    }
}
