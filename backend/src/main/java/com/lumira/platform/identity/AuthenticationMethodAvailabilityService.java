package com.lumira.platform.identity;

import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.WechatAvailabilityPort;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Resolves readiness from real providers before a primary method can enter stored policy. */
@Service
public class AuthenticationMethodAvailabilityService
        implements AuthenticationMethodAvailabilityPort {
    private final MailSender mail;
    private final SmsSender sms;
    private final PasskeyPolicyPort passkeys;
    private final ObjectProvider<WechatAvailabilityPort> wechatProviders;

    public AuthenticationMethodAvailabilityService(
            MailSender mail,
            SmsSender sms,
            PasskeyPolicyPort passkeys,
            ObjectProvider<WechatAvailabilityPort> wechatProviders) {
        this.mail = mail;
        this.sms = sms;
        this.passkeys = passkeys;
        this.wechatProviders = wechatProviders;
    }

    @Override
    public Set<PrimaryMethod> availableMethods() {
        var methods = EnumSet.of(PrimaryMethod.PASSWORD);
        if (passkeyReady()) methods.add(PrimaryMethod.PASSKEY);
        if (emailReady()) methods.add(PrimaryMethod.EMAIL_OTP);
        if (smsReady()) methods.add(PrimaryMethod.SMS_OTP);
        if (wechatReady()) methods.add(PrimaryMethod.WECHAT);
        return Set.copyOf(methods);
    }

    private boolean passkeyReady() {
        var policy = passkeys.currentPolicy();
        return policy != null
                && Boolean.TRUE.equals(policy.enabled())
                && Boolean.TRUE.equals(policy.passwordlessEnabled());
    }

    private boolean emailReady() {
        return mail.ready();
    }

    private boolean smsReady() {
        return sms.ready();
    }

    private boolean wechatReady() {
        var provider = wechatProviders.getIfAvailable();
        if (provider == null) return false;
        String appId = provider.currentAppId();
        return provider.ready() && appId != null && !appId.isBlank();
    }
}
