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
