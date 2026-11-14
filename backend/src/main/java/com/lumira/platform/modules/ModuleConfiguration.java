/**
 * 汇集各功能模块自行声明的描述信息并组装注册表，避免新增模块时修改中央业务清单。 Assembles the registry from descriptors contributed by
 * feature modules, avoiding a central business-module list that must change for every addition.
 */
package com.lumira.platform.modules;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModuleConfiguration {
    /**
     * 内核只装配各功能模块提供的描述符；新增功能应添加自身配置，无需修改此中央装配类。
     *
     * <p>The kernel only assembles module descriptors contributed by feature modules. A feature is
     * therefore added by adding its own configuration, rather than editing this central class.
     */
    @Bean
    ModuleRegistry registry(java.util.List<PlatformModule> modules) {
        return new ModuleRegistry(modules);
    }
}
