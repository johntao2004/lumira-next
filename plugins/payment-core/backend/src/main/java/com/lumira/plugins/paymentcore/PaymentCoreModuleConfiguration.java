package com.lumira.plugins.paymentcore;

import com.lumira.platform.modules.PlatformModule;
import com.lumira.plugin.sdk.PluginResources;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PaymentCoreModuleConfiguration {
    @Bean
    PlatformModule paymentCoreModule() {
        return new PlatformModule("payment-core", "支付核心与订单", "1.0.7", List.of("platform"));
    }

    @Bean
    PaymentCoreReconciliation paymentCoreReconciliation(
            PluginResources resources, PaymentCoreService service) {
        return new PaymentCoreReconciliation(resources, service);
    }

    static final class PaymentCoreReconciliation {
        PaymentCoreReconciliation(PluginResources resources, PaymentCoreService service) {
            resources.schedule(
                    "payment-core-reconcile", Duration.ofSeconds(30), service::reconcile);
        }
    }
}
