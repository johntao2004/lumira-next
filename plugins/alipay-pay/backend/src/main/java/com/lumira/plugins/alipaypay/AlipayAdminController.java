package com.lumira.plugins.alipaypay;

import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.plugin.sdk.common.PaymentChannelAdminController;
import com.lumira.plugin.sdk.common.PaymentCoreAdminPort;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ModuleEndpoint("alipay-pay")
@RestController
@RequestMapping("/api/plugins/alipay-pay")
public final class AlipayAdminController extends PaymentChannelAdminController {
    public AlipayAdminController(PluginExtensionsPort extensions) {
        super(
                extensions,
                PaymentCoreAdminPort.Page.ALIPAY_ORDERS,
                PaymentCoreAdminPort.Page.ALIPAY_SETTINGS);
    }
}
