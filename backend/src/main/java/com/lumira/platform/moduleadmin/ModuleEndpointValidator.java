/**
 * 启动时校验宿主应用接口均声明了已注册模块归属，使遗漏的端点契约在接收请求前失败。 Validates registered module ownership for host
 * application handlers during startup so missing endpoint contracts fail before requests are
 * served.
 */
package com.lumira.platform.moduleadmin;

import com.lumira.platform.modules.ModuleRegistry;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class ModuleEndpointValidator implements SmartInitializingSingleton {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final ModuleRegistry registry;

    public ModuleEndpointValidator(
            @Qualifier("requestMappingHandlerMapping")
                    ObjectProvider<RequestMappingHandlerMapping> mappings,
            ModuleRegistry registry) {
        this.mappings = mappings;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        mappings.getObject().getHandlerMethods().values().stream()
                // Spring Boot 的 BasicErrorController 属于框架，没有 Lumira 模块归属；模块归属契约只约束应用处理器。
                // Spring Boot's BasicErrorController is framework-owned and has no Lumira module.
                // Only application handlers participate in the module ownership contract.
                .filter(
                        handler ->
                                handler.getBeanType()
                                        .getPackageName()
                                        .startsWith("com.lumira.platform."))
                .forEach(handler -> ModuleAccessInterceptor.validate(handler, registry));
    }
}
