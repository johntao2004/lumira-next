package com.lumira.platform.modules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods = false)
public class ModuleConfiguration {
 /**
  * The kernel only assembles module descriptors contributed by feature
  * modules.  A feature is therefore added by adding its own configuration,
  * rather than editing this central class.
  */
 @Bean ModuleRegistry registry(java.util.List<PlatformModule> modules){return new ModuleRegistry(modules);}
}
