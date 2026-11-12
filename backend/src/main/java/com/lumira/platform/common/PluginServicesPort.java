package com.lumira.platform.common;

import java.util.function.Function;

/**
 * Synchronous exact-owner service calls. Callers must return values, never retain plugin instances.
 */
public interface PluginServicesPort {
    <T extends PluginServicePort, R> R service(
            String owner, Class<T> contract, Function<T, R> call);
}
