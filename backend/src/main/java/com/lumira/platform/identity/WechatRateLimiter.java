package com.lumira.platform.identity;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits public WeChat attempt throttles independently of a later failed attempt transaction. */
@Service
public class WechatRateLimiter {
    private final FixedWindowBuckets buckets;
    private final ClientAddressResolver addresses;
    private final PublicAuthCrypto crypto;

    public WechatRateLimiter(
            FixedWindowBuckets buckets, ClientAddressResolver addresses, PublicAuthCrypto crypto) {
        this.buckets = buckets;
        this.addresses = addresses;
        this.crypto = crypto;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String sessionDigest, HttpServletRequest request) {
        String source = crypto.sourceDigest(addresses.resolve(request));
        return buckets.consume(FixedWindowBuckets.key("wechat-session", sessionDigest), 8)
                && buckets.consume(FixedWindowBuckets.key("wechat-source", source), 60);
    }
}
