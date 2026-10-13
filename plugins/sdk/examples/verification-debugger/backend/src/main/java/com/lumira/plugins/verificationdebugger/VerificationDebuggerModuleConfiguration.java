package com.lumira.plugins.verificationdebugger;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class VerificationDebuggerModuleConfiguration {
    @Bean
    PlatformModule verificationDebuggerModule() {
        return new PlatformModule("verification-debugger", "验证调试器", "1.0.3", List.of("platform"));
    }
}
