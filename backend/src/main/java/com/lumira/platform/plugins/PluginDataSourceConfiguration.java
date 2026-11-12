package com.lumira.platform.plugins;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PluginDataSourceConfiguration {
    @Bean
    static BeanPostProcessor pluginFencedDataSources() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource source
                                && !(source instanceof PluginFencedDataSource)
                        ? new PluginFencedDataSource(source)
                        : bean;
            }
        };
    }
}
