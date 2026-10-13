package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class PluginRuntimeReadinessConfigurationTest {
    @Test
    void readinessProbeIncludesRuntimeAndDatabaseWhileLivenessStaysIndependent() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var configuration = yaml.getObject();

        assertThat(configuration).isNotNull();
        assertThat(configuration.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,pluginRuntime");
        assertThat(configuration.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
    }
}
