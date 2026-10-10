package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginRuntimeReadinessConfigurationTest {
    @Test
    void readinessProbeIncludesRuntimeAndDatabaseWhileLivenessStaysIndependent()
            throws IOException {
        String configuration;
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertThat(stream).isNotNull();
            configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration)
                .contains(
                        "    health:\n"
                                + "      probes:\n"
                                + "        enabled: true\n"
                                + "      group:\n"
                                + "        readiness:\n"
                                + "          include: readinessState,db,pluginRuntime\n"
                                + "        liveness:\n"
                                + "          include: livenessState\n"
                                + "      show-details: never\n");
    }
}
