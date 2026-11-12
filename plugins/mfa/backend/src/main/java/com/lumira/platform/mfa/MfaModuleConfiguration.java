package com.lumira.platform.mfa;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Descriptor for the optional authenticator and second-factor module. */
@Configuration(proxyBeanMethods = false)
public class MfaModuleConfiguration {
    @Bean
    PlatformModule mfaModule() {
        return new PlatformModule("mfa", "二次认证与验证器", "1.0.11", List.of("identity"));
    }
}
