package com.lumira.plugins.wechatpay;

import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.plugin.sdk.common.PaymentChannelAdminController;
import com.lumira.plugin.sdk.common.PaymentCoreAdminPort;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ModuleEndpoint("wechat-pay")
@RestController
@RequestMapping("/api/plugins/wechat-pay")
public final class WechatPayAdminController extends PaymentChannelAdminController {
    public WechatPayAdminController(PluginExtensionsPort extensions) {
        super(
                extensions,
                PaymentCoreAdminPort.Page.WECHAT_ORDERS,
                PaymentCoreAdminPort.Page.WECHAT_SETTINGS);
    }
}
