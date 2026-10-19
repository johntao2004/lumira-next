package com.lumira.plugins.wechatpay;

import com.lumira.platform.modules.PlatformModule;
import com.lumira.plugin.sdk.PluginEntry;
import java.util.List;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

public final class WechatPayPlugin extends PluginEntry {
    public WechatPayPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return WechatPayConfiguration.class;
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.lumira.plugins.wechatpay")
    public static class WechatPayConfiguration {
        @Bean(destroyMethod = "close")
        WechatPayGateway wechatPayGateway() {
            return new WechatPayGateway();
        }

        @Bean
        PlatformModule wechatPayModule() {
            return new PlatformModule(
                    "wechat-pay", "微信扫码支付", "1.0.7", List.of("platform", "payment-core"));
        }
    }
}
