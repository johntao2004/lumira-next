package com.lumira.platform.identity;

import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP-to-kernel adapter for the privacy-preserving CAPTCHA request facts. */
final class CaptchaRequestFacts {
    private CaptchaRequestFacts() {}

    static CaptchaProofPort.RequestFacts from(
            HttpServletRequest request,
            ClientAddressResolver addresses,
            PublicAuthCrypto crypto,
            boolean createSession) {
        if (request == null) return CaptchaProofPort.RequestFacts.none();

        String address = addresses.resolve(request);
        String sourceDigest = crypto.sourceDigest(address);
        var sourceKeys = new LinkedHashMap<String, String>();
        putSourceKey(sourceKeys, "source", address);
        putSourceKey(sourceKeys, "login-source", sourceDigest);
        putSourceKey(sourceKeys, "reg-source", sourceDigest);
        putSourceKey(sourceKeys, "regcomp-source", sourceDigest);
        putSourceKey(sourceKeys, "smsrec-source", sourceDigest);
        putSourceKey(sourceKeys, "mfa-source", address);
        putSourceKey(sourceKeys, "wechat-source", sourceDigest);
        putSourceKey(sourceKeys, "recovery-source", address);
        putSourceKey(sourceKeys, "captcha-source", address);

        HttpSession session = createSession ? request.getSession() : request.getSession(false);
        if (session == null) return new CaptchaProofPort.RequestFacts(null, sourceKeys, Map.of());

        String sessionId = session.getId();
        String sessionDigest =
                FixedWindowBuckets.key("captcha-session", sessionId)
                        .substring("captcha-session:".length());
        String sessionRiskDigest = crypto.sessionDigest(sessionId);
        var sessionKeys = new LinkedHashMap<String, String>();
        putSessionKey(sessionKeys, "captcha-session", sessionId);
        putSessionKey(sessionKeys, "login-session", sessionRiskDigest);
        putSessionKey(sessionKeys, "reg-session", sessionRiskDigest);
        putSessionKey(sessionKeys, "regcomp-session", sessionRiskDigest);
        putSessionKey(sessionKeys, "smsrec-session", sessionRiskDigest);
        putSessionKey(sessionKeys, "wechat-session", sessionRiskDigest);
        putSessionKey(sessionKeys, "mfa-session", sessionId);
        return new CaptchaProofPort.RequestFacts(sessionDigest, sourceKeys, sessionKeys);
    }

    private static void putSourceKey(Map<String, String> target, String namespace, String subject) {
        target.put(namespace, FixedWindowBuckets.key(namespace, subject));
    }

    private static void putSessionKey(
            Map<String, String> target, String namespace, String subject) {
        target.put(namespace, FixedWindowBuckets.key(namespace, subject));
    }
}
