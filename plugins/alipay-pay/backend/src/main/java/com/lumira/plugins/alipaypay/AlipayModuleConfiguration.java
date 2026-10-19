package com.lumira.plugins.alipaypay;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AlipayModuleConfiguration {
    @Bean
    PlatformModule alipayPayModule() {
        return new PlatformModule(
                "alipay-pay", "支付宝扫码支付", "1.0.7", List.of("platform", "payment-core"));
    }
}
