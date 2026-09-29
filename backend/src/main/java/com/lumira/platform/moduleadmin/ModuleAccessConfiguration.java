package com.lumira.platform.moduleadmin;
import com.lumira.platform.modules.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class ModuleAccessConfiguration implements WebMvcConfigurer {
 private final ModuleRegistry registry;private final ModuleRuntime runtime;
 public ModuleAccessConfiguration(ModuleRegistry registry,ModuleRuntime runtime){this.registry=registry;this.runtime=runtime;}
 @Override public void addInterceptors(InterceptorRegistry interceptors){interceptors.addInterceptor(new ModuleAccessInterceptor(registry,runtime));}
}
